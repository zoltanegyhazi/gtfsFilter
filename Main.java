package gtfsFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Használat: java gtfsFilter.Main <forrás_mappa> <cél_zip> <route_type> [route_type ...]");
            System.err.println("  pl.      java gtfsFilter.Main .. ./metro.zip 1");
            System.exit(1);
        }

        Path srcDir = Path.of(args[0]);
        Path targetZip = Path.of(args[1]);
        Set<String> routeTypes = Set.copyOf(Arrays.asList(args).subList(2, args.length));

        // a szűrt txt-k ideiglenes mappába kerülnek, zipelés után töröljük
        Path workDir = Files.createTempDirectory("gtfsFilter");
        try {
            new GtfsFilter(srcDir, workDir, routeTypes).run(targetZip);
            System.out.println("Kész: " + targetZip.toAbsolutePath());
        } finally {
            try (Stream<Path> walk = Files.walk(workDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
