package io.github.vvb2060.callrecording.xposed;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * JVM unit tests for package-private helper logic in {@link Init}.
 *
 * <p>These tests do not require an Android device or emulator. They verify:
 * <ul>
 *   <li>Disclosure audio asset name matching ({@link Init#isDisclosureAudioAsset}).</li>
 *   <li>Unsupported recording country-code list ({@link Init#isUnsupportedRecordingCountryCode}).</li>
 *   <li>Built-in silent WAV byte format (minimal RIFF header validation).</li>
 * </ul>
 */
public class InitHelpersTest {

    // -------------------------------------------------------------------------
    // isDisclosureAudioAsset
    // -------------------------------------------------------------------------

    @Test
    public void disclosureAsset_recordWav_isMatch() {
        assertTrue(Init.isDisclosureAudioAsset("recording_disclosure.wav"));
    }

    @Test
    public void disclosureAsset_disclosureOgg_isMatch() {
        assertTrue(Init.isDisclosureAudioAsset("disclosure_prompt.ogg"));
    }

    @Test
    public void disclosureAsset_announcementMp3_isMatch() {
        assertTrue(Init.isDisclosureAudioAsset("call_announcement.mp3"));
    }

    @Test
    public void disclosureAsset_mixedCase_isMatch() {
        assertTrue(Init.isDisclosureAudioAsset("CallRecordingDisclosure.wav"));
    }

    @Test
    public void disclosureAsset_noKeyword_noMatch() {
        assertFalse(Init.isDisclosureAudioAsset("ringtone.wav"));
    }

    @Test
    public void disclosureAsset_noAudioExtension_noMatch() {
        assertFalse(Init.isDisclosureAudioAsset("recording_disclosure.xml"));
    }

    @Test
    public void disclosureAsset_null_noMatch() {
        assertFalse(Init.isDisclosureAudioAsset(null));
    }

    @Test
    public void disclosureAsset_empty_noMatch() {
        assertFalse(Init.isDisclosureAudioAsset(""));
    }

    // -------------------------------------------------------------------------
    // isUnsupportedRecordingCountryCode
    // -------------------------------------------------------------------------

    @Test
    public void countryCode_null_isUnsupported() {
        assertTrue(Init.isUnsupportedRecordingCountryCode(null));
    }

    @Test
    public void countryCode_empty_isUnsupported() {
        assertTrue(Init.isUnsupportedRecordingCountryCode(""));
    }

    @Test
    public void countryCode_whitespace_isUnsupported() {
        assertTrue(Init.isUnsupportedRecordingCountryCode("   "));
    }

    @Test
    public void countryCode_de_isUnsupported() {
        assertTrue(Init.isUnsupportedRecordingCountryCode("DE"));
    }

    @Test
    public void countryCode_lowerCase_de_isUnsupported() {
        assertTrue(Init.isUnsupportedRecordingCountryCode("de"));
    }

    @Test
    public void countryCode_fr_isUnsupported() {
        assertTrue(Init.isUnsupportedRecordingCountryCode("FR"));
    }

    @Test
    public void countryCode_us_isSupported() {
        assertFalse(Init.isUnsupportedRecordingCountryCode("US"));
    }

    @Test
    public void countryCode_gb_isSupported() {
        assertFalse(Init.isUnsupportedRecordingCountryCode("GB"));
    }

    @Test
    public void countryCode_au_isSupported() {
        assertFalse(Init.isUnsupportedRecordingCountryCode("AU"));
    }

    @Test
    public void countryCode_unknown_isSupported() {
        // Unknown codes are treated as potentially supported (allow-list approach).
        assertFalse(Init.isUnsupportedRecordingCountryCode("XX"));
    }

    // -------------------------------------------------------------------------
    // Built-in silent WAV
    // -------------------------------------------------------------------------

    @Test
    public void builtinWav_isNotEmpty() {
        assertNotNull(Init.wav);
        assertTrue("built-in WAV must contain at least a RIFF header", Init.wav.length >= 44);
    }

    @Test
    public void builtinWav_hasRiffMagic() {
        byte[] w = Init.wav;
        // Bytes 0-3: "RIFF"
        assertEquals('R', w[0]);
        assertEquals('I', w[1]);
        assertEquals('F', w[2]);
        assertEquals('F', w[3]);
    }

    @Test
    public void builtinWav_hasWaveMagic() {
        byte[] w = Init.wav;
        // Bytes 8-11: "WAVE"
        assertEquals('W', w[8]);
        assertEquals('A', w[9]);
        assertEquals('V', w[10]);
        assertEquals('E', w[11]);
    }

    @Test
    public void builtinWav_hasFmtChunk() {
        byte[] w = Init.wav;
        // Bytes 12-15: "fmt "
        assertEquals('f', w[12]);
        assertEquals('m', w[13]);
        assertEquals('t', w[14]);
        assertEquals(' ', w[15]);
    }

    @Test
    public void builtinWav_hasDataChunk() {
        byte[] w = Init.wav;
        // Bytes 36-39: "data" (for a standard 44-byte header with a 16-byte fmt chunk)
        assertEquals('d', w[36]);
        assertEquals('a', w[37]);
        assertEquals('t', w[38]);
        assertEquals('a', w[39]);
    }

    // -------------------------------------------------------------------------
    // HookStatus enum completeness
    // -------------------------------------------------------------------------

    @Test
    public void hookStatus_allExpectedValuesPresent() {
        // Regression check: these six status values must remain defined.
        assertNotNull(Init.HookStatus.INSTALLED);
        assertNotNull(Init.HookStatus.SKIPPED);
        assertNotNull(Init.HookStatus.FAILED);
        assertNotNull(Init.HookStatus.AMBIGUOUS);
        assertNotNull(Init.HookStatus.OBSERVE_ONLY);
        assertNotNull(Init.HookStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // GroupResult
    // -------------------------------------------------------------------------

    @Test
    public void groupResult_noDegradationWhenAllInstalled() {
        Init.GroupResult g = new Init.GroupResult("test");
        g.put("h1", Init.HookStatus.INSTALLED);
        g.put("h2", Init.HookStatus.INSTALLED);
        assertFalse(g.hasDegradation());
    }

    @Test
    public void groupResult_degradationOnFailed() {
        Init.GroupResult g = new Init.GroupResult("test");
        g.put("h1", Init.HookStatus.INSTALLED);
        g.put("h2", Init.HookStatus.FAILED);
        assertTrue(g.hasDegradation());
    }

    @Test
    public void groupResult_degradationOnNotFound() {
        Init.GroupResult g = new Init.GroupResult("test");
        g.put("h1", Init.HookStatus.NOT_FOUND);
        assertTrue(g.hasDegradation());
    }

    @Test
    public void groupResult_degradationOnAmbiguous() {
        Init.GroupResult g = new Init.GroupResult("test");
        g.put("h1", Init.HookStatus.AMBIGUOUS);
        assertTrue(g.hasDegradation());
    }

    @Test
    public void groupResult_noDegraationOnSkipped() {
        Init.GroupResult g = new Init.GroupResult("test");
        g.put("h1", Init.HookStatus.SKIPPED);
        assertFalse(g.hasDegradation());
    }

    @Test
    public void groupResult_toStringContainsId() {
        Init.GroupResult g = new Init.GroupResult("mygroup");
        g.put("h1", Init.HookStatus.INSTALLED);
        assertTrue(g.toString().contains("mygroup"));
    }
}
