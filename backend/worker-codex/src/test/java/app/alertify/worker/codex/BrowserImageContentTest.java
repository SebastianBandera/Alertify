package app.alertify.worker.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.google.protobuf.ByteString;
import app.alertify.worker.ai.grpc.ChatImage;

class BrowserImageContentTest {
    @Test
    void keepsExistingTextMessagesAndIncludesAttachedTextWithAnImage() {
        assertThat(CodexChatService.messageContent("existing chat", List.of())).isEqualTo("existing chat");
        ChatImage image = ChatImage.newBuilder().setMediaType("image/jpeg").setData(ByteString.copyFrom(new byte[] { 1, 2, 3 })).build();
        Object content = CodexChatService.messageContent("Check this button", List.of(image));
        assertThat((List<?>) content).hasSize(2);
        assertThat(((List<?>) content).getFirst()).isEqualTo(Map.of("type", "input_text", "text", "Check this button"));
        assertThat(((List<?>) content).get(1)).isEqualTo(Map.of("type", "input_image", "image_url", "data:image/jpeg;base64,AQID", "detail", "auto"));
    }

    @Test
    void rejectsNonImagePayloadsBeforeCallingTheProvider() {
        ChatImage image = ChatImage.newBuilder().setMediaType("text/html").setData(ByteString.copyFromUtf8("not an image")).build();
        assertThatThrownBy(() -> CodexChatService.messageContent("", List.of(image))).isInstanceOf(IllegalArgumentException.class);
    }
}
