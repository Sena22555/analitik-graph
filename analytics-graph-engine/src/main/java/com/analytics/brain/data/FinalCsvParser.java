package com.analytics.brain.data;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.StringReader;
import java.util.ArrayList;

public final class FinalCsvParser implements InterfaceDataParser {

    @Override
    public void parse(FinalDataset ds, String text) throws Exception {
        text = text.replace("﻿", "");
        String firstLine = text.lines().findFirst().orElse("");
        char delimiter = guessDelimiter(firstLine);

        CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .setAllowMissingColumnNames(true)
                .setIgnoreSurroundingSpaces(true)
                .build()
                .parse(new StringReader(text));

        ds.header = new ArrayList<>();
        int unnamed = 1;
        for (String name : parser.getHeaderNames()) {
            ds.header.add(name == null || name.isBlank() ? "Колонка " + unnamed++ : name.trim());
        }

        ds.records = new ArrayList<>();
        for (CSVRecord record : parser) {
            String[] row = new String[ds.header.size()];
            for (int i = 0; i < row.length; i++) {
                row[i] = i < record.size() ? record.get(i) : "";
            }
            ds.records.add(row);
        }
        parser.close();
    }
    private static char guessDelimiter(String headerLine) {
        char best = ',';
        int bestCount = countOutsideQuotes(headerLine, ',');
        for (char candidate : new char[]{';', '\t', '|'}) {
            int count = countOutsideQuotes(headerLine, candidate);
            if (count > bestCount) {
                bestCount = count;
                best = candidate;
            }
        }
        return best;
    }
    private static int countOutsideQuotes(String line, char symbol) {
        int count = 0;
        boolean inQuotes = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && ch == symbol) {
                count++;
            }
        }
        return count;
    }
}
