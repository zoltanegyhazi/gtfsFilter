package gtfsFilter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GtfsFilter
 *
 * GTFS feed szűrése route_type alapján (lásd README.md).
 * A filter* metódusokat a README-ben leírt sorrendben kell hívni,
 * mert mindegyik az előzőek által összegyűjtött id-halmazokra szűr.
 */
public class GtfsFilter {

    private final Path srcDir; // eredeti GTFS txt-k mappája
    private final Path workDir; // ide kerülnek a szűrt txt-k zipelés előtt
    private final Set<String> routeTypes;

    // a kaszkád során gyűjtött id-k
    private final Set<String> routeIds = new HashSet<>();
    private final Set<String> agencyIds = new HashSet<>();
    private final Set<String> tripIds = new HashSet<>();
    private final Set<String> serviceIds = new HashSet<>();
    private final Set<String> stopIds = new HashSet<>();

    public GtfsFilter(Path srcDir, Path workDir, Set<String> routeTypes) {
        this.srcDir = srcDir;
        this.workDir = workDir;
        this.routeTypes = routeTypes;
    }

    /** Végigfuttatja a teljes kaszkádot, majd bezipeli az eredményt. */
    public void run(Path targetZip) throws IOException {
        Files.createDirectories(workDir);
        filterRoutes();
        filterAgency();
        filterTrips();
        filterStopTimes();
        filterStops();
        filterCalendarDates();
        zipOutput(targetZip);
    }

    // ---- fájlonkénti szűrők (ebben a sorrendben) ----

    /** 1. routes.txt: route_type ∈ routeTypes -> routeIds, agencyIds */
    void filterRoutes() throws IOException {
        Path in = srcDir.resolve("routes.txt"); // honnan olvasok
        Path out = workDir.resolve("routes.txt"); // hova írok

        try (BufferedReader reader = Files.newBufferedReader(in, StandardCharsets.UTF_8);
                BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            writer.write(header);
            writer.newLine();

            List<String> columns = parseCsvLine(header);
            int routeTypeIdx = columnIndex(columns, "route_type");
            int routeIdIdx = columnIndex(columns, "route_id");
            int agencyIdIdx = columnIndex(columns, "agency_id"); // opcionális oszlop
            if (routeTypeIdx < 0 || routeIdIdx < 0) {
                throw new IOException("routes.txt: hiányzik a route_type vagy a route_id oszlop");
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> fields = parseCsvLine(line);
                String routeType = fields.get(routeTypeIdx).trim();
                if (!routeTypes.contains(routeType)) {
                    continue;
                }

                writer.write(line); // az eredeti sort írjuk ki, változatlanul
                writer.newLine();

                routeIds.add(fields.get(routeIdIdx));
                if (agencyIdIdx >= 0 && !fields.get(agencyIdIdx).isEmpty()) {
                    agencyIds.add(fields.get(agencyIdIdx));
                }
            }
        }

        if (routeIds.isEmpty()) {
            System.err.println("Figyelem: egy route sem felel meg a megadott route_type-oknak: " + routeTypes);
        }
    }

    /** 2. agency.txt: agency_id ∈ agencyIds */
    void filterAgency() throws IOException {
        filterFile("agency.txt", "agency_id", agencyIds, false, Map.of());
    }

    /** 3. trips.txt: route_id ∈ routeIds -> tripIds, serviceIds */
    void filterTrips() throws IOException {
        filterFile("trips.txt", "route_id", routeIds, false,
                Map.of("trip_id", tripIds, "service_id", serviceIds));
    }

    /** 4. stop_times.txt (nagy fájl, streamelve!): trip_id ∈ tripIds -> stopIds */
    void filterStopTimes() throws IOException {
        filterFile("stop_times.txt", "trip_id", tripIds, false, Map.of("stop_id", stopIds));
    }

    /** 5. stops.txt: stop_id ∈ stopIds + parent_station-jeik (két kör) */
    void filterStops() throws IOException {
        // 1. kör: a szülőállomások hozzáadása a stopIds-hez.
        // A fájl kicsi, ezért memóriába olvassuk, és addig ismételjük,
        // amíg nem kerül be új id (a szülőnek is lehet szülője).
        List<String> lines = Files.readAllLines(srcDir.resolve("stops.txt"), StandardCharsets.UTF_8);
        List<String> columns = parseCsvLine(lines.get(0));
        int stopIdIdx = columnIndex(columns, "stop_id");
        int parentIdx = columnIndex(columns, "parent_station");

        if (parentIdx >= 0) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String line : lines.subList(1, lines.size())) {
                    if (line.isBlank()) {
                        continue;
                    }
                    List<String> fields = parseCsvLine(line);
                    String parent = fields.get(parentIdx);
                    if (stopIds.contains(fields.get(stopIdIdx)) && !parent.isEmpty()) {
                        changed |= stopIds.add(parent);
                    }
                }
            }
        }

        // 2. kör: kiírás a kibővített stopIds alapján
        filterFile("stops.txt", "stop_id", stopIds, false, Map.of());
    }

    /** 6. calendar_dates.txt (opcionális): service_id ∈ serviceIds */
    void filterCalendarDates() throws IOException {
        filterFile("calendar_dates.txt", "service_id", serviceIds, true, Map.of());
    }

    // ---- segédfüggvények ----

    /**
     * Egy CSV sor mezőkre bontása, idézőjelek kezelésével
     * (idézőjeles mezőben lehet vessző).
     */
    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"'); // "" = idézőjel a mezőn belül
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString()); // utolsó mező (üres is lehet)
        return fields;
    }

    /**
     * Az oszlop indexe a fejlécben név alapján (BOM-ot levágva).
     * Ha nincs ilyen oszlop, -1.
     */
    static int columnIndex(List<String> header, String columnName) {
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).trim();
            if (name.startsWith("﻿")) {
                name = name.substring(1); // BOM levágása
            }
            if (name.equals(columnName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Általános szűrő: soronként olvassa a fileName fájlt a srcDir-ből,
     * a column oszlop értéke alapján (∈ keep) változatlanul kiírja a sort
     * a workDir-be azonos néven. A fejlécet mindig kiírja.
     * Ha a fájl nem létezik és optional, csak kihagyja.
     *
     * collect: oszlopnév -> halmaz; a megtartott sorokból ezeknek az
     * oszlopoknak az értékét gyűjti (a következő fájl szűréséhez).
     */
    void filterFile(String fileName, String column, Set<String> keep, boolean optional,
            Map<String, Set<String>> collect) throws IOException {
        Path in = srcDir.resolve(fileName);
        Path out = workDir.resolve(fileName);

        if (!Files.exists(in)) {
            if (optional) {
                System.err.println("Kihagyva (nincs ilyen fájl): " + fileName);
                return;
            }
            throw new IOException("Hiányzó kötelező fájl: " + in);
        }

        try (BufferedReader reader = Files.newBufferedReader(in, StandardCharsets.UTF_8);
                BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            writer.write(header);
            writer.newLine();

            List<String> columns = parseCsvLine(header);
            int keepIdx = columnIndex(columns, column);
            if (keepIdx < 0) {
                throw new IOException(fileName + ": hiányzik a(z) " + column + " oszlop");
            }

            // a gyűjtendő oszlopok indexei, egyszer kikeresve
            Map<Integer, Set<String>> collectIdx = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : collect.entrySet()) {
                int idx = columnIndex(columns, e.getKey());
                if (idx >= 0) {
                    collectIdx.put(idx, e.getValue());
                }
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> fields = parseCsvLine(line);
                if (!keep.contains(fields.get(keepIdx))) {
                    continue;
                }

                writer.write(line); // az eredeti sort írjuk ki, változatlanul
                writer.newLine();

                for (Map.Entry<Integer, Set<String>> e : collectIdx.entrySet()) {
                    String value = fields.get(e.getKey());
                    if (!value.isEmpty()) {
                        e.getValue().add(value);
                    }
                }
            }
        }
    }

    /** A workDir-ben lévő txt-k bezipelése targetZip-be (kisbetűs nevekkel). */
    void zipOutput(Path targetZip) throws IOException {
        Path parent = targetZip.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<Path> files;
        try (Stream<Path> list = Files.list(workDir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList();
        }

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(targetZip))) {
            for (Path file : files) {
                zip.putNextEntry(new ZipEntry(file.getFileName().toString().toLowerCase()));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }
}
