package com.vocahq.vocaphone.local

import android.util.Log
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import java.util.concurrent.atomic.AtomicLong
import com.vocahq.vocaphone.VocaPhoneApplication
import com.vocahq.vocaphone.ime.VocaPhoneInputMethodService
import com.vocahq.vocaphone.ime.commitDictationText
import com.vocahq.vocaphone.dictation.InsertionOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import androidx.test.platform.app.InstrumentationRegistry
import com.vocahq.vocaphone.audio.SpeechAudioConditioning
import java.io.File
import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Downloads a real pinned model onto the device and transcribes real audio with
 * it, which is the only way to prove the native engine, the pins and the
 * verification markers agree with each other.
 *
 * Opt-in, because it needs the network and pulls tens of megabytes:
 *
 *     ./gradlew :app:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.localModelE2E=true
 */
class LocalEngineEndToEndTest {
    /** Model storage belongs to the app under test. */
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The sample wav is packaged in the test APK, not the app. */
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun aPinnedWhisperModelDownloadsVerifiesAndTranscribes() = runBlocking {
        assumeTrue(
            "set -Pandroid.testInstrumentationRunnerArguments.localModelE2E=true to run",
            InstrumentationRegistry.getArguments().getString("localModelE2E") == "true",
        )
        val manager = LocalModelManager(context)
        val model = requireNotNull(LocalModelCatalog.find("tiny-q8_0"))

        manager.download(model)
        assertTrue("download did not report success", manager.isDownloaded(model.id))

        val directory = manager.directoryFor(model)
        // The cheap path must accept what the digest path just committed.
        LocalModelIntegrity.verifySizes(model, directory)
        assertTrue(File(directory, LocalModelIntegrity.VERIFIED_MARKER).isFile)

        val transcript = manager.transcribe(speech(), model.id, "en").text
        assertTrue("empty transcript from $directory", transcript.isNotBlank())
        assertTrue(
            "unexpected transcript: $transcript",
            transcript.lowercase().contains("yellow lamps"),
        )

        // A second pass must reuse the loaded context rather than reloading.
        assertEquals(transcript, manager.transcribe(speech(), model.id, "en").text)
    }

    @Test
    fun fourierDownloadsAndTranscribesOnPhysicalPhone() = runBlocking {
        val manager = LocalModelManager(context)
        val model = requireNotNull(LocalModelCatalog.find("oruk-fourier-2b"))
        val device = android.os.Build.MODEL
        Log.i("FourierPhysical", "start device=$device abi=${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        manager.download(model)
        assertTrue("Model failed download and verification", manager.isDownloaded(model.id))
        LocalModelIntegrity.verifySizes(model, manager.directoryFor(model))
        val readyFile = File(context.filesDir, "fourier-download-ready")
        readyFile.writeText("verified")
        Log.i("FourierPhysical", "ten files verified; awaiting network test")
        val networkMarker = File(context.filesDir, "fourier-network-checked")
        val deadline = System.currentTimeMillis() + 90_000
        while (!networkMarker.exists() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(1000)
        }
        repeat(2) { index ->
            val started = android.os.SystemClock.elapsedRealtime()
            val text = manager.transcribe(speech(), model.id, "en").text
            Log.i("FourierPhysical", "pass=${index + 1} runtimeMs=${android.os.SystemClock.elapsedRealtime() - started} transcript=$text")
            assertTrue("unexpected Fourier result: $text", text.lowercase().contains("yellow lamps"))
        }
    }

    @Test
    fun fourierRestartsDownloadAndInsertsInExternalEditor() = runBlocking {
        val m = LocalModelManager(context)
        val model = requireNotNull(LocalModelCatalog.find("oruk-fourier-2b"))
        val initialFree = context.filesDir.usableSpace
        val prefix = File(m.directoryFor(model).parentFile, ".${model.id}.staging")
        val first = m.startDownload(model)
        withTimeout(240_000) {
            while (m.state.value.downloadedBytes < 32_000_000) delay(250)
        }
        val atInterrupt = m.state.value.downloadedBytes
        m.cancelDownload(DownloadCancelReason.TIME_LIMIT)
        first.join()
        val retained = prefix.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        Log.i("FourierPhysical", "interrupt reportedBytes=$atInterrupt retainedBytes=$retained downloaded=${m.isDownloaded(model.id)} ${m.state.value.message}")
        val problems = mutableListOf<String>()
        if (retained < 20_000_000) problems.add("only $retained bytes retained after interruption")
        val resumed = AtomicLong(0)
        val monitor = launch(Dispatchers.Default) {
            m.state.collect { state -> resumed.updateAndGet { maxOf(it, state.resumedBytes) } }
        }
        m.download(model)
        monitor.cancelAndJoin()
        if (resumed.get() == 0L) problems.add("zero recovered bytes reported on resumed transfer")
        LocalModelIntegrity.verifySizes(model, m.directoryFor(model))
        val installed = m.directoryFor(model).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        Log.i("FourierPhysical", "resume retainedBytes=$retained resumedBytes=${resumed.get()} installedBytes=$installed freeBeforeBytes=$initialFree freeAfterBytes=${context.filesDir.usableSpace}")

        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(cmd: String): String = automation.executeShellCommand(cmd).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() }
        }
        val oldIme = shell("settings get secure default_input_method")
        val oldServices = shell("settings get secure enabled_accessibility_services")
        val oldA11y = shell("settings get secure accessibility_enabled")
        val pkg = context.packageName
        val imeId = "$pkg/.ime.VocaPhoneInputMethodService"
        val accessId = "$pkg/$pkg.accessibility.VocaPhoneAccessibilityService"
        val container = VocaPhoneApplication.container(context)
        val ext = testContext.packageName
        val extComponent = ComponentName(ext, "com.vocahq.vocaphone.local.FourierPhysicalEditor")
        fun openEditor() {
            context.startActivity(Intent().setComponent(extComponent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        suspend fun textInEditor(): String {
            val root = automation.rootInActiveWindow ?: return ""
            return root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)?.text?.toString().orEmpty()
        }
        suspend fun awaitEditor(fragment: String) {
            withTimeout(18_000) {
                while (!textInEditor().contains(fragment, ignoreCase = true)) delay(200)
            }
        }
        try {
            container.settings.setDisclosureAccepted(true)
            shell("ime enable $imeId")
            shell("ime set $imeId")
            shell("settings put secure enabled_accessibility_services ${if (oldServices.isBlank() || oldServices == "null") accessId else "$oldServices:$accessId"}")
            shell("settings put secure accessibility_enabled 1")
            openEditor()
            val transcript = m.transcribe(speech(), model.id, "en").text
            assertTrue("Expected real Fourier fixture text", transcript.contains("yellow lamps", ignoreCase = true))
            val memory = File("/proc/self/status").readLines().filter {
                it.startsWith("VmHWM:") || it.startsWith("VmRSS:")
            }.joinToString(" ")
            Log.i("FourierPhysical", "post-decode process memory $memory")
            runCatching {
                withTimeout(25_000) {
                    while (container.dictation.imeInserter?.currentTargetPackage() != ext) delay(250)
                }
                val ime = container.dictation.imeInserter as VocaPhoneInputMethodService
                val result = withContext(Dispatchers.Main) {
                    commitDictationText(requireNotNull(ime.currentInputConnection), transcript, false)
                }
                require(result != null)
                awaitEditor("yellow lamps")
                Log.i("FourierPhysical", "keyboard externalField=PASS app=$ext text=${textInEditor()}")
            }.onFailure { e ->
                problems.add("keyboard: $e")
                Log.e("FourierPhysical", "keyboard insertion failed: $e")
            }
            runCatching {
                openEditor()
                withTimeout(20_000) {
                    while (container.dictation.floatingInserter?.currentTargetPackage() != ext || textInEditor().isNotEmpty()) delay(200)
                }
                val inserter = requireNotNull(container.dictation.floatingInserter)
                val result = inserter.insert(transcript)
                check(result.outcome == InsertionOutcome.INSERTED) { "overlay outcome ${result.outcome}" }
                awaitEditor("yellow lamps")
                Log.i("FourierPhysical", "overlay externalField=PASS app=$ext text=${textInEditor()}")
            }.onFailure { e ->
                problems.add("overlay: $e")
                Log.e("FourierPhysical", "overlay insertion failed: $e")
            }
        } finally {
            if (oldIme.isNotBlank() && oldIme != "null") shell("ime set $oldIme")
            if (oldServices == "null" || oldServices.isBlank()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $oldServices")
            if (oldA11y in listOf("0","1")) shell("settings put secure accessibility_enabled $oldA11y")
            container.settings.setDisclosureAccepted(false)
        }
        assertTrue(problems.joinToString(), problems.isEmpty())
    }

    @Test
    fun xOverlayInsertsTheVerifiedFixtureInExternalEditor() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = automation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() }
        }
        val pkg = context.packageName
        check(pkg.endsWith(".x")) { "The floating service ships only in the X variant: $pkg" }
        val app = VocaPhoneApplication.container(context)
        val ext = testContext.packageName
        val oldServices = shell("settings get secure enabled_accessibility_services")
        val oldFlag = shell("settings get secure accessibility_enabled")
        val accessId = "$pkg/com.vocahq.vocaphone.accessibility.VocaPhoneAccessibilityService"
        val given = "After early nightfall, the yellow lamps would light up here and there the squalid quarter of the brothels."
        try {
            app.settings.setDisclosureAccepted(true)
            shell("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
            val services = if (oldServices.isBlank() || oldServices == "null") accessId else "$oldServices:$accessId"
            shell("settings put secure enabled_accessibility_services $services")
            shell("settings put secure accessibility_enabled 1")
            context.startActivity(Intent().setComponent(ComponentName(ext, "com.vocahq.vocaphone.local.FourierPhysicalEditor"))
                .putExtra("showKeyboard", false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            var attempts = 0
            withTimeout(35_000) {
                while (app.dictation.floatingInserter?.currentTargetPackage() != ext) {
                    if (attempts++ % 15 == 0) Log.i("FourierPhysical", "overlayReady=${app.dictation.floatingInserter != null} target=${app.dictation.floatingInserter?.currentTargetPackage()} root=${automation.rootInActiveWindow?.packageName} component=$accessId")
                    delay(200)
                }
            }
            val outcome = requireNotNull(app.dictation.floatingInserter).insert(given)
            check(outcome.outcome == InsertionOutcome.INSERTED) { "overlay outcome ${outcome.outcome}" }
            withTimeout(12_000) {
                while (true) {
                    val node = automation.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                    val actual = node?.text?.toString().orEmpty()
                    if (actual.trim() == given) {
                        Log.i("FourierPhysical", "X floating overlay externalField=PASS app=$ext text=$actual")
                        break
                    }
                    delay(200)
                }
            }
        } finally {
            if (oldServices == "null" || oldServices.isBlank()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $oldServices")
            if (oldFlag in listOf("0","1")) shell("settings put secure accessibility_enabled $oldFlag")
            app.settings.setDisclosureAccepted(false)
        }
    }

    /**
     * Repeatable physical-device timing for a model that is already downloaded
     * in the app under test. This deliberately does not assert a duration: the
     * same model spans phones with radically different CPUs, while the log gives
     * us comparable numbers for the same audio before and after a change.
     *
     *     ./gradlew :app:connectedFullDebugAndroidTest \
     *       -Pandroid.testInstrumentationRunnerArguments.localModelBenchmark=small
     */
    @Test
    fun anInstalledWhisperModelTranscribesReferenceAudio() = runBlocking {
        val modelID = InstrumentationRegistry.getArguments()
            .getString("localModelBenchmark")
            .orEmpty()
        assumeTrue(
            "set localModelBenchmark to an installed Whisper model ID",
            modelID.isNotEmpty(),
        )
        val manager = LocalModelManager(context)
        val model = requireNotNull(LocalModelCatalog.find(modelID))
        require(model.engine == LocalModelEngine.WHISPER) { "$modelID is not a Whisper model" }
        manager.refresh()
        assumeTrue("$modelID is not downloaded in the target app", manager.isDownloaded(model.id))

        val arguments = InstrumentationRegistry.getArguments()
        val threads = arguments.getString("localModelBenchmarkThreads")
            ?.toIntOrNull()
            ?: WhisperCpuConfig.preferredThreadCount(model.id)
        val audio = SpeechAudioConditioning.condition(speech())
        val audioContext = arguments.getString("localModelBenchmarkContext")
            ?.toIntOrNull()
            ?: if (model.cropsAudioContext) WhisperCpuConfig.whisperAudioContext(audio.size) else 0
        val pointer = WhisperLib.initContext(
            File(manager.directoryFor(model), model.primaryFile.path).absolutePath,
        )
        check(pointer != 0L) { "Could not load ${model.displayName}" }
        try {
            repeat(2) { run ->
                val elapsed = measureTime {
                    check(
                        WhisperLib.fullTranscribe(
                            pointer,
                            threads,
                            audio,
                            "en",
                            false,
                            0,
                            1f,
                            audioContext,
                            "",
                        ) == 0,
                    ) { "${model.displayName} could not decode the reference audio" }
                }
                val transcript = WhisperText.decode(WhisperLib.getText(pointer)).trim()
                assertTrue("empty transcript from ${model.displayName}", transcript.isNotBlank())
                assertTrue(
                    "unexpected transcript: $transcript",
                    transcript.lowercase().contains("yellow lamps"),
                )
                Log.i(
                    "VocaPhoneBenchmark",
                    "${model.id} run ${run + 1}: ${elapsed.inWholeMilliseconds} ms; " +
                        "threads=$threads; audio_ctx=$audioContext; $transcript",
                )
            }
        } finally {
            WhisperLib.freeContext(pointer)
        }
    }

    /**
     * 16 kHz mono PCM, as the recorder produces it. The reference transcription
     * is "after early nightfall the yellow lamps would light up here and there
     * the squalid quarter of the brothels".
     */
    private fun speech(): FloatArray {
        val bytes = testContext.assets.open(SAMPLE).use { it.readBytes() }
        val pcm = bytes.drop(44)
        val samples = FloatArray(pcm.size / 2)
        for (index in samples.indices) {
            val low = pcm[index * 2].toInt() and 0xFF
            val high = pcm[index * 2 + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort() / 32_768f
        }
        return samples
    }

    private companion object {
        const val SAMPLE = "local-engine-sample.wav"
    }
}
