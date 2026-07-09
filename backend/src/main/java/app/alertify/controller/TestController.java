package app.alertify.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {
	
	@GetMapping("prueba")
	public ResponseEntity<String> prueba() {
		return ResponseEntity.ok("Prueba 12 " + Math.random());
	}
}
