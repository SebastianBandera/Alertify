package test.app;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import app.crypto.AES;
import app.crypto.Crypto;
import app.crypto.CryptoMessage;
import app.crypto.RandomGenerator;
import app.crypto.SHA256;

public class CryptoTest {

	@Test
    void testRandomBytesGenerator() {
		for (int i = 0; i <= 32; i++) {
			byte[] gen = RandomGenerator.generarBytesAleatorios(i);
			
			assertNotNull(gen);
			assertEquals(gen.length, i);
		}
	}
	

	@Test
    void testRandomBytesGeneratorException() {
		assertThrows(RuntimeException.class, () -> RandomGenerator.generarBytesAleatorios(-1), "Se esperaba una excepción al utilizar un parámetro negativo.");
	}

	@Test
    void testRandomTextBase64Generator() {
		for (int i = 0; i <= 32; i++) {
			String gen = RandomGenerator.generarTextoAleatorio(i);
			
			assertNotNull(gen);
			assertEquals(gen.length(), i);
		}
	}

	@Test
    void testRandomTextGeneratorException() {
		assertThrows(RuntimeException.class, () -> RandomGenerator.generarTextoAleatorio(-1), "Se esperaba una excepción al utilizar un parámetro negativo.");
	}

	@Test
    void testSHA256() throws Exception {
		for (int i = 0; i <= 100; i=i+10) {
			String gen = RandomGenerator.generarTextoAleatorio(i);
			
			String genSHA = new String(SHA256.hashSHA256(gen), StandardCharsets.ISO_8859_1);
			
			assertNotNull(genSHA);
			assertEquals(genSHA.length(), 32);
		}
	}
	

	@Test
    void testSHA256Null() throws Exception {
		String genSHA = new String(SHA256.hashSHA256(null), StandardCharsets.ISO_8859_1);
		
		assertNotNull(genSHA);
		assertEquals(genSHA.length(), 32);
	}
	
	@Test
	void testAES1() throws Exception {
		String msg = RandomGenerator.generarTextoAleatorio(2048);
		String clave = RandomGenerator.generarTextoAleatorio(32);
		String iv = RandomGenerator.generarTextoAleatorio(16);
		
		String encriptado = AES.encriptarAES(msg, clave, iv);
		
		assertNotNull(encriptado);
		assertFalse(encriptado.trim().isEmpty());
		
		String descriptado = AES.desencriptarAES(encriptado, clave, iv);
		
		assertNotNull(descriptado);
		assertFalse(encriptado.trim().isEmpty());
		
		assertEquals(msg, descriptado);
	}
	
	@Test
	void testAES2() throws Exception {
		String msg = RandomGenerator.generarTextoAleatorio(5000);
		String clave = RandomGenerator.generarTextoAleatorio(32);
		String iv = RandomGenerator.generarTextoAleatorio(16);
		
		String encriptado = AES.encriptarAES(msg, clave, iv);
		
		assertNotNull(encriptado);
		assertFalse(encriptado.trim().isEmpty());
		
		String descriptado = AES.desencriptarAES(encriptado, clave, iv);
		
		assertNotNull(descriptado);
		assertFalse(encriptado.trim().isEmpty());
		
		assertEquals(msg, descriptado);
	}
	
	@Test
	void testAES3() throws Exception {
		String msg = RandomGenerator.generarTextoAleatorio(15);
		String clave = RandomGenerator.generarTextoAleatorio(32);
		String iv = RandomGenerator.generarTextoAleatorio(16);
		
		String encriptado = AES.encriptarAES(msg, clave, iv);
		
		assertNotNull(encriptado);
		assertFalse(encriptado.trim().isEmpty());
		
		String descriptado = AES.desencriptarAES(encriptado, clave, iv);
		
		assertNotNull(descriptado);
		assertFalse(encriptado.trim().isEmpty());
		
		assertEquals(msg, descriptado);
	}
	
	@Test
	void testAESNull() throws Exception {
		assertThrows(Exception.class, () -> AES.encriptarAES(null, null, null), "Se esperaba una excepción al utilizar un parámetro nulo.");
	}
	
	@Test
	void testAESEmptyl() throws Exception {
		String msg = "";
		String clave = RandomGenerator.generarTextoAleatorio(32);
		String iv = RandomGenerator.generarTextoAleatorio(16);
		
		String encriptado = AES.encriptarAES(msg, clave, iv);
		
		assertNotNull(encriptado);
		assertFalse(encriptado.trim().isEmpty());
		
		String desencriptado = AES.desencriptarAES(encriptado, clave, iv);
		
		assertNotNull(desencriptado);
		assertTrue(desencriptado.trim().isEmpty());
		
		assertEquals(msg, desencriptado);
	}
	
	@Test
	void testCrypto() throws Exception {
		String msg = RandomGenerator.generarTextoAleatorio(15);
		String clave = RandomGenerator.generarTextoAleatorio(32);
		
		CryptoMessage cm = Crypto.encriptar(msg, clave);
		
		assertNotNull(cm);
		assertNotNull(cm.getMessage());
		assertNotNull(cm.getIv());
		assertFalse(cm.getMessage().trim().isEmpty());
		assertFalse(cm.getIv().trim().isEmpty());
		
		String desencriptado = Crypto.desencriptar(cm.getMessage(), clave, cm.getIv());
		
		assertNotNull(desencriptado);
		assertFalse(desencriptado.trim().isEmpty());
		
		assertEquals(msg, desencriptado);
	}
	
	@Test
	void testCryptoNotEqual() throws Exception {
		String msg = RandomGenerator.generarTextoAleatorio(15);
		String clave = RandomGenerator.generarTextoAleatorio(32);
		
		CryptoMessage cm1 = Crypto.encriptar(msg, clave);
		
		assertNotNull(cm1);
		assertNotNull(cm1.getMessage());
		assertNotNull(cm1.getIv());
		assertFalse(cm1.getMessage().trim().isEmpty());
		assertFalse(cm1.getIv().trim().isEmpty());
		
		String desencriptado1 = Crypto.desencriptar(cm1.getMessage(), clave, cm1.getIv());
		
		assertNotNull(desencriptado1);
		assertFalse(desencriptado1.trim().isEmpty());
		
		assertEquals(msg, desencriptado1);
		
		CryptoMessage cm2 = Crypto.encriptar(msg, clave);
		
		assertNotNull(cm2);
		assertNotNull(cm2.getMessage());
		assertNotNull(cm2.getIv());
		assertFalse(cm2.getMessage().trim().isEmpty());
		assertFalse(cm2.getIv().trim().isEmpty());
		
		String desencriptado2 = Crypto.desencriptar(cm2.getMessage(), clave, cm2.getIv());
		
		assertNotNull(desencriptado2);
		assertFalse(desencriptado2.trim().isEmpty());
		
		assertEquals(msg, desencriptado2);
		
		
		assertNotEquals(cm1, cm2);
		assertNotEquals(cm1.toString(), cm2.toString());
		assertNotEquals(cm1.hashCode(), cm2.hashCode());
	}
	
	@Test
	void testCryptoExceptionInput() {
		assertThrows(Exception.class, () -> Crypto.encriptar(null, null), "Se esperaba una excepción al utilizar un parámetro nulo.");
		assertThrows(Exception.class, () -> Crypto.encriptar(null, ""), "Se esperaba una excepción al utilizar un parámetro nulo.");
		assertThrows(Exception.class, () -> Crypto.encriptar("", null), "Se esperaba una excepción al utilizar un parámetro nulo.");
	}
	
	@Test
	void testCryptoMessageEquals() {
		CryptoMessage cm1 = new CryptoMessage("a", "b");
		CryptoMessage cm2 = new CryptoMessage("a", "c");
		CryptoMessage cm3 = new CryptoMessage("c", "b");
		CryptoMessage cm4 = new CryptoMessage("c", "c");
		CryptoMessage cm5 = new CryptoMessage("a", "b");
		
		assertNotEquals(cm1, null);
		assertNotEquals(cm1, "");
		assertNotEquals(cm1, cm2);
		assertNotEquals(cm1, cm3);
		assertNotEquals(cm1, cm4);

		assertEquals(cm1, cm5);
		assertEquals(cm1, cm1);
	}
}
