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
