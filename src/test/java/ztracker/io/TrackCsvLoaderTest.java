package ztracker.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ztracker.io.TrackCsvLoader.ColumnConfig;
import ztracker.io.TrackCsvLoader.CsvConfig;
import ztracker.model.TrackData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TrackCsvLoaderTest {

    private static Path writeCsv(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void trackMateFormat_headerZeroSkipThree_defaultRadiusApplied(@TempDir Path dir) throws IOException {
        String csv = "X,Y,FRAME,TRACK_ID\n"
                + "unit,unit,unit,unit\n"
                + "0,0,0,0\n"
                + "0,0,0,0\n"
                + "10.5,20.5,0,1\n"
                + "11.0,21.0,1,1\n";
        Path file = writeCsv(dir, "trackmate.csv", csv);
        CsvConfig config = CsvConfig.defaults(); // header=0, skip=3, defaultRadius=3.5

        ColumnConfig cols = TrackCsvLoader.detectColumns(file, config);
        TrackData data = TrackCsvLoader.load(file, config, cols);

        assertEquals(2, data.x.length);
        assertArrayEquals(new double[]{10.5, 11.0}, data.x);
        assertArrayEquals(new double[]{20.5, 21.0}, data.y);
        assertArrayEquals(new int[]{0, 1}, data.frame);
        assertArrayEquals(new double[]{3.5, 3.5}, data.radius); // no radius column present
    }

    @Test
    void radiusCell_nanOrInfinite_fallsBackToTheDefault_notThroughToTheSampler(@TempDir Path dir)
            throws IOException {
        // Double.parseDouble("NaN") and ("Infinity") both SUCCEED, so neither reaches the
        // catch — the same trap parseCoordinate guards for X/Y and this missed. A NaN radius
        // made ZSampler take zero samples and the detection was reported "out of bounds",
        // blaming the coordinates; an INFINITE radius is worse, since
        // (int) Math.ceil(Infinity) == Integer.MAX_VALUE and the disk loop then runs ~1.8e19
        // iterations, hanging Fiji with no error at all.
        String csv = "X,Y,FRAME,TRACK_ID,RADIUS\n"
                + "1.0,1.0,0,1,NaN\n"
                + "2.0,2.0,1,1,Infinity\n"
                + "3.0,3.0,2,1,-Infinity\n"
                + "4.0,4.0,3,1,\n"          // blank — already fell back before this patch
                + "5.0,5.0,4,1,garbage\n"   // unparseable — likewise
                + "6.0,6.0,5,1,2.5\n";      // a real radius must still come through
        Path file = writeCsv(dir, "radius.csv", csv);
        CsvConfig config = new CsvConfig(0, 0, 3.5);

        ColumnConfig cols = TrackCsvLoader.detectColumns(file, config);
        TrackData data = TrackCsvLoader.load(file, config, cols);

        assertEquals(6, data.radius.length);
        assertArrayEquals(new double[]{3.5, 3.5, 3.5, 3.5, 3.5, 2.5}, data.radius);
        for (double r : data.radius) {
            assertFalse(Double.isNaN(r), "no radius may reach the sampler as NaN");
            assertFalse(Double.isInfinite(r), "no radius may reach the sampler as infinite");
        }
    }

    @Test
    void radiusCell_notPositive_alsoFallsBackToTheDefault(@TempDir Path dir) throws IOException {
        // Leaving non-positive values out left an arbitrary split: -1.0 and below make the
        // disk loop never run (zero samples, then misreported as out of bounds), while -0.4
        // and 0 ceil to 0 and quietly sample ONE pixel — succeeding as if SINGLE_PIXEL had
        // been chosen. The silently-succeeding case is the worse of the two.
        String csv = "X,Y,FRAME,TRACK_ID,RADIUS\n"
                + "1.0,1.0,0,1,-1.0\n"
                + "2.0,2.0,1,1,-0.4\n"
                + "3.0,3.0,2,1,0\n"
                + "4.0,4.0,3,1,0.0\n"
                + "5.0,5.0,4,1,0.25\n";   // the smallest POSITIVE radius must survive intact
        Path file = writeCsv(dir, "radius_np.csv", csv);
        CsvConfig config = new CsvConfig(0, 0, 3.5);

        TrackData data = TrackCsvLoader.load(
                file, config, TrackCsvLoader.detectColumns(file, config));

        assertArrayEquals(new double[]{3.5, 3.5, 3.5, 3.5, 0.25}, data.radius);
        assertEquals(5, data.x.length, "the rows themselves are kept — only the radius changes");
    }

    @Test
    void nonTrackMateFormat_aliasColumnsNoMetadataRows(@TempDir Path dir) throws IOException {
        // Alias-based, case-insensitive detection — not hard-coded to TrackMate's
        // exact header names, and no metadata rows to skip.
        String csv = "id,t,x,y\n"
                + "1,1,5.0,6.0\n"
                + "1,2,5.5,6.5\n";
        Path file = writeCsv(dir, "other_tracker.csv", csv);
        CsvConfig config = new CsvConfig(0, 0, 3.5);

        ColumnConfig cols = TrackCsvLoader.detectColumns(file, config);
        TrackData data = TrackCsvLoader.load(file, config, cols);

        assertEquals(2, data.x.length);
        assertArrayEquals(new int[]{1, 2}, data.frame);
    }

    @Test
    void rowsWithBlankOrNaNFrameOrTrackId_areSkipped(@TempDir Path dir) throws IOException {
        String csv = "X,Y,FRAME,TRACK_ID\n"
                + "unit,unit,unit,unit\n"
                + "0,0,0,0\n"
                + "0,0,0,0\n"
                + "1.0,1.0,0,1\n"
                + "2.0,2.0,,1\n"      // blank frame -> skipped
                + "3.0,3.0,1,NaN\n"   // NaN track id -> skipped
                + "4.0,4.0,1,1\n";
        Path file = writeCsv(dir, "with_gaps.csv", csv);
        CsvConfig config = CsvConfig.defaults();

        ColumnConfig cols = TrackCsvLoader.detectColumns(file, config);
        TrackData data = TrackCsvLoader.load(file, config, cols);

        assertEquals(2, data.x.length);
        assertArrayEquals(new double[]{1.0, 4.0}, data.x);
    }

    @Test
    void rowsWithBlankOrNaNOrMalformedXY_areSkipped(@TempDir Path dir) throws IOException {
        String csv = "X,Y,FRAME,TRACK_ID\n"
                + "unit,unit,unit,unit\n"
                + "0,0,0,0\n"
                + "0,0,0,0\n"
                + "1.0,1.0,0,1\n"
                + ",5.0,1,1\n"        // blank X -> skipped
                + "5.0,,2,1\n"        // blank Y -> skipped
                + "NaN,5.0,3,1\n"     // literal "NaN" X -> skipped (parses but is NaN)
                + "5.0,garbage,4,1\n" // unparseable Y -> skipped
                + "6.0,6.0,5,1\n";
        Path file = writeCsv(dir, "bad_xy.csv", csv);
        CsvConfig config = CsvConfig.defaults();

        ColumnConfig cols = TrackCsvLoader.detectColumns(file, config);
        TrackData data = TrackCsvLoader.load(file, config, cols);

        // Only the two rows with genuinely valid X and Y survive.
        assertEquals(2, data.x.length);
        assertArrayEquals(new double[]{1.0, 6.0}, data.x);
        assertArrayEquals(new double[]{1.0, 6.0}, data.y);
        assertArrayEquals(new int[]{0, 5}, data.frame);
    }
}
