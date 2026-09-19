package com.jobportal.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Unit tests for the CSV field/row builder used by the employer applications export
// (new feature). No Spring context needed - plain string in, string out.
class CsvTest {

    @Test
    void plainFieldIsJustQuoted() {
        assertThat(Csv.field("Priya Sharma")).isEqualTo("\"Priya Sharma\"");
    }

    @Test
    void nullFieldBecomesAnEmptyQuotedField() {
        assertThat(Csv.field(null)).isEqualTo("\"\"");
    }

    @Test
    void embeddedQuotesAreDoubled() {
        assertThat(Csv.field("6\" screen")).isEqualTo("\"6\"\" screen\"");
    }

    @Test
    void embeddedCommaSurvivesInsideTheQuotedField() {
        assertThat(Csv.field("Acme, Inc.")).isEqualTo("\"Acme, Inc.\"");
    }

    // OWASP "CSV Injection": a field opening with =, +, - or @ is executed as a formula
    // by Excel/Sheets - each is neutralised with a leading apostrophe that forces the
    // whole value to be read as text, BEFORE the normal quoting is applied.
    @Test
    void fieldStartingWithEqualsIsNeutralised() {
        assertThat(Csv.field("=HYPERLINK(\"http://evil.example\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://evil.example\"\")\"");
    }

    @Test
    void fieldStartingWithPlusIsNeutralised() {
        assertThat(Csv.field("+1+1")).isEqualTo("\"'+1+1\"");
    }

    @Test
    void fieldStartingWithMinusIsNeutralised() {
        assertThat(Csv.field("-2+3")).isEqualTo("\"'-2+3\"");
    }

    @Test
    void fieldStartingWithAtIsNeutralised() {
        assertThat(Csv.field("@SUM(1,2)")).isEqualTo("\"'@SUM(1,2)\"");
    }

    // A formula trigger character NOT in the first position is left alone - only the
    // leading character makes a spreadsheet read a cell as a formula.
    @Test
    void triggerCharacterInTheMiddleIsNotNeutralised() {
        assertThat(Csv.field("Team=A")).isEqualTo("\"Team=A\"");
    }

    @Test
    void rowJoinsFieldsWithCommasAndEndsWithCrLf() {
        assertThat(Csv.row("Reference", "Candidate", "Status")).isEqualTo("\"Reference\",\"Candidate\",\"Status\"\r\n");
    }

    @Test
    void rowNeutralisesEveryDangerousFieldInPlace() {
        assertThat(Csv.row("APP-00001", "=SUM(A1:A2)", "Applied"))
                .isEqualTo("\"APP-00001\",\"'=SUM(A1:A2)\",\"Applied\"\r\n");
    }
}
