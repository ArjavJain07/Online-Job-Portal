package com.jobportal.web.support;

// RFC 4180 field/row building for the employer applications CSV export (new feature: see
// EmployerApplicationController#export), with one deliberate addition on top of plain
// quoting: OWASP's "CSV Injection" mitigation. Every field here can be candidate- or
// employer-supplied text (a full name, a job title) reaching a file the employer will
// open in Excel/Sheets/LibreOffice, and any of those will try to evaluate a cell as a
// FORMULA when its first character is one that starts a formula there (=, +, - or @) -
// so a candidate named e.g. "=HYPERLINK(...)" could otherwise run an arbitrary formula
// the moment the employer opens the export. Prefixing such a field with a leading
// apostrophe forces spreadsheet software to treat the whole value as plain text instead,
// the standard fix for exactly this (OWASP CSV Injection cheat sheet).
public final class Csv {

    private static final String FORMULA_TRIGGERS = "=+-@";

    private Csv() {
    }

    // One escaped, quoted field: RFC 4180 quoting (wrap in double quotes, double any
    // quote already inside the value) always applied, not only when a comma/quote/newline
    // is present - simpler than deciding case by case and just as valid CSV.
    public static String field(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && FORMULA_TRIGGERS.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    // One CSV row (CRLF line ending, RFC 4180) from already-ordered column values.
    public static String row(String... fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(field(fields[i]));
        }
        return line.append("\r\n").toString();
    }
}
