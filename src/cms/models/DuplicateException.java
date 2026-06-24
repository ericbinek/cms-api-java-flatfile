package cms.models;

import java.util.List;

public final class DuplicateException extends RuntimeException {

    private final List<String> details;

    public DuplicateException(List<String> details) {
        super("Unique key collision.");
        this.details = details;
    }

    public List<String> details() {
        return details;
    }
}
