package app.alertify.controller;

import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.alertify.ai.chat.AiChatService;
import app.alertify.ai.memory.AiMemoryManagementService;
import app.alertify.ai.api.AiPage;
import app.alertify.codex.AiModuleService;

@RestController
@RequestMapping("/api/ai")
@PreAuthorize(app.alertify.config.AuthorizationPolicies.ADMIN)
public class AiModuleController {

    private final AiModuleService service;
    private final AiChatService chats;
    private final AiMemoryManagementService memories;

    public AiModuleController(AiModuleService service, AiChatService chats, AiMemoryManagementService memories) {
        this.service = service;
        this.chats = chats;
        this.memories = memories;
    }

    @GetMapping("/module")
    public AiModuleService.ModuleResponse module() {
        return service.module();
    }

    @PutMapping("/settings")
    public AiModuleService.SettingsResponse update(@RequestBody AiModuleService.SettingsRequest request) {
        return service.update(request);
    }

    @PostMapping("/codex/login/browser")
    public AiModuleService.BrowserLoginResponse browserLogin(@RequestBody BrowserLoginRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.beginBrowser(request.mode(), jwt.getSubject());
    }

    @GetMapping(value = "/codex/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    @PreAuthorize("permitAll()")
    public ResponseEntity<String> callback(@RequestParam String code, @RequestParam String state, @RequestParam(defaultValue = "") String scope, @RequestParam(name = "client_id", defaultValue = "") String clientId) {
        service.completeBrowser(code, state, scope, clientId);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("<!doctype html><html lang=\"es\"><meta charset=\"utf-8\"><title>Alertify</title><body><p>Sesi&oacute;n iniciada. Pod&eacute;s cerrar esta pesta&ntilde;a.</p><script>window.close()</script></body></html>");
    }

    @PostMapping("/codex/oauth/callback/relay")
    @PreAuthorize("permitAll()")
    public AiModuleService.OperationResponse extensionCallback(@RequestBody ExtensionCallbackRequest request) {
        return service.completeExtension(request.callbackTicket(), request.code(), request.state(), request.scope(), request.clientId());
    }

    @GetMapping(value = "/codex/extensions/{browser}", produces = "application/zip")
    public ResponseEntity<byte[]> extension(@PathVariable String browser) {
        var extension = service.extension(browser);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(extension.fileName()).build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(extension.contents().length)
                .body(extension.contents());
    }

    @PostMapping("/codex/login/device")
    public AiModuleService.DeviceLoginResponse deviceLogin() {
        return service.beginDevice();
    }

    @GetMapping("/codex/login/device")
    public AiModuleService.DeviceLoginResponse deviceLoginState() {
        return service.device();
    }

    @DeleteMapping("/codex/login-attempt")
    public AiModuleService.OperationResponse cancelLogin() {
        return service.cancel();
    }

    @PostMapping("/codex/refresh")
    public AiModuleService.RefreshResponse refresh() {
        return service.refresh();
    }

    @DeleteMapping("/codex/session")
    public AiModuleService.OperationResponse logout() {
        return service.logout();
    }

    @PostMapping("/codex/test")
    public AiModuleService.OperationResponse test() {
        return service.test();
    }

    @GetMapping("/chat/conversations")
    public List<AiChatService.ConversationSummary> conversations(Authentication authentication) {
        return chats.conversations(authentication);
    }

    @GetMapping("/chat/conversations/{id}")
    public AiChatService.ConversationDetail conversation(@PathVariable Long id, Authentication authentication) {
        return chats.conversation(authentication, id);
    }

    @GetMapping("/chat/management/conversations")
    public List<AiChatService.ManagedConversationSummary> managedConversations() {
        return chats.managedConversations();
    }

    @GetMapping("/chat/management/conversations/{id}")
    public AiChatService.ManagedConversationDetail managedConversation(@PathVariable Long id) {
        return chats.managedConversation(id);
    }

    @DeleteMapping("/chat/management/conversations")
    public ResponseEntity<Void> deleteAllManagedConversations() {
        chats.deleteAllManagedConversations();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/chat/management/conversations/{id}")
    public ResponseEntity<Void> deleteManagedConversation(@PathVariable Long id) {
        chats.deleteManagedConversation(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/chat/management/memories")
    public AiPage<AiMemoryManagementService.Summary> managedMemories(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return memories.list(page, size);
    }

    @GetMapping("/chat/management/memories/{id}")
    public AiMemoryManagementService.Detail managedMemory(@PathVariable Long id) {
        return memories.get(id);
    }

    @DeleteMapping("/chat/management/memories/{id}")
    public ResponseEntity<Void> deleteManagedMemory(@PathVariable Long id, @RequestParam long version) {
        memories.delete(id, version);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/chat/management/memories")
    public ResponseEntity<Void> deleteAllManagedMemories() {
        memories.deleteAll();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/chat/turns")
    public AiChatService.SubmitResponse submit(@RequestBody AiChatService.SubmitRequest request, Authentication authentication) {
        return chats.submit(authentication, request);
    }

    @PostMapping("/chat/tool-invocations/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable Long id, Authentication authentication) {
        chats.approve(authentication, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/chat/tool-invocations/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable Long id, Authentication authentication) {
        chats.reject(authentication, id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/chat/turns/{id}")
    public ResponseEntity<Void> cancelTurn(@PathVariable Long id, Authentication authentication) {
        chats.cancel(authentication, id);
        return ResponseEntity.noContent().build();
    }

    public record BrowserLoginRequest(String mode) {
    }

    public record ExtensionCallbackRequest(String callbackTicket, String code, String state, String scope, String clientId) {
    }
}
