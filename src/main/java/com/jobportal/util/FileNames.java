package com.jobportal.util;

// Turns a user-supplied file name into a safe display name (Section 7.4). This is only
// ever used for the human-readable name shown back to the user (for example
// JobApplication.resumeOriginalName) - the file on disk always keeps its own UUID name,
// never this one, so nothing here needs to be unique or path-safe by itself.
public final class FileNames {

    private static final int MAX_LENGTH = 150;

    private FileNames() {
    }

    // Removes any path the browser sent (keeps only the last segment), drops control
    // characters and double quotes (both could break the Content-Disposition header the
    // file is later served with), and cuts the result to 150 characters.
    public static String sanitise(String originalName) {
        if (originalName == null) {
            return "";
        }

        String name = originalName.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }

        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '"' && !Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }

        String result = cleaned.toString().trim();
        if (result.length() > MAX_LENGTH) {
            result = result.substring(0, MAX_LENGTH);
        }
        return result;
    }
}
