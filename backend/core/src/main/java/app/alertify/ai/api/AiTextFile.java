package app.alertify.ai.api;

/** Small text file transported through a tool without host paths or URLs. */
public record AiTextFile(String fileName, String contentType, String content) {
    public AiTextFile {
        if (fileName == null || fileName.isBlank())
            throw new IllegalArgumentException("File name must not be blank");

        if (contentType == null || contentType.isBlank())
            throw new IllegalArgumentException("Content type must not be blank");

        if (content == null)
            throw new IllegalArgumentException("File content is required");
    }
}
