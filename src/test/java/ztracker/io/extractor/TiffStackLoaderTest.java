package ztracker.io.extractor;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TiffStackLoaderTest {

    @Test
    void extractFrameNumber_usesTrailingNumber_ignoringIncidentalDigitsEarlierInName() {
        // "32" in "32bit" must not be mistaken for the frame index.
        assertEquals(7, TiffStackLoader.extractFrameNumber(new File("z_origin_32bit_0007.tif")));
    }

    @Test
    void extractFrameNumber_plainNumericFilename() {
        assertEquals(42, TiffStackLoader.extractFrameNumber(new File("0042.tif")));
    }

    @Test
    void extractFrameNumber_noDigits_fallsBackToZero() {
        assertEquals(0, TiffStackLoader.extractFrameNumber(new File("frame.tif")));
    }
}
