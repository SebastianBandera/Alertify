package app.alertify.ai;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import app.alertify.ai.api.AiFilter;
import app.alertify.ai.api.AiTextFile;

/** Safe adapters shared by tool facades. */
@Service
public class AiToolSupport {

    private static final String CSV_CONTENT_TYPE = "text/csv;charset=UTF-8";

    private final int maxTextBytes;

    public AiToolSupport(@Value("${ai.tools.max-text-bytes:262144}") int maxTextBytes) {
        if (maxTextBytes < 1)
            throw new IllegalArgumentException("ai.tools.max-text-bytes must be positive");

        this.maxTextBytes = maxTextBytes;
    }

    public AiTextFile csv(String fileName, byte[] content) {
        requireSize(content.length);
        return new AiTextFile(fileName, CSV_CONTENT_TYPE, new String(content, StandardCharsets.UTF_8));
    }

    public MultipartFile csvUpload(AiTextFile file) {
        if (!CSV_CONTENT_TYPE.equalsIgnoreCase(file.contentType()) && !"text/csv".equalsIgnoreCase(file.contentType()))
            throw new IllegalArgumentException("Only CSV text files are accepted");

        byte[] content = file.content().getBytes(StandardCharsets.UTF_8);
        requireSize(content.length);
        return new TextMultipartFile(file.fileName(), file.contentType(), content);
    }

    public MultiValueMap<String, String> filters(List<AiFilter> filters) {
        LinkedMultiValueMap<String, String> result = new LinkedMultiValueMap<>();
        if (filters != null)
            filters.forEach(filter -> result.add(filter.field(), filter.value()));

        return result;
    }

    private void requireSize(int size) {
        if (size > maxTextBytes)
            throw new IllegalArgumentException("AI text file exceeds the configured limit of " + maxTextBytes + " bytes");
    }

    private record TextMultipartFile(String originalFilename, String contentType, byte[] content) implements MultipartFile {
        private TextMultipartFile {
            content = content.clone();
        }

        @Override
        public String getName() { return "file"; }

        @Override
        public String getOriginalFilename() { return originalFilename; }

        @Override
        public String getContentType() { return contentType; }

        @Override
        public boolean isEmpty() { return content.length == 0; }

        @Override
        public long getSize() { return content.length; }

        @Override
        public byte[] getBytes() { return content.clone(); }

        @Override
        public InputStream getInputStream() { return new ByteArrayInputStream(content); }

        @Override
        public void transferTo(File destination) throws IOException {
            Files.write(destination.toPath(), content);
        }
    }
}
