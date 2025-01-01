package app.crypto;

import java.nio.charset.StandardCharsets;

public class Crypto {

	private Crypto() {
		
	}
	
	public static CryptoMessage encriptar(String texto, String claveTexto) throws Exception {
		if (texto == null || claveTexto == null) throw new Exception(new NullPointerException("Entrada nula al encriptar"));
		
		String iv = RandomGenerator.generarTextoAleatorio(16);
		
		byte[] clavehash = SHA256.hashSHA256(claveTexto);
		
		String clavehashtext = new String(clavehash, StandardCharsets.ISO_8859_1);
		
		String encriptado = AES.encriptarAES(texto, clavehashtext, iv);
		
		return new CryptoMessage(encriptado, iv);
	}
	

	public static String desencriptar(String texto, String claveTexto, String iv) throws Exception {
		byte[] clavehash = SHA256.hashSHA256(claveTexto);
		
		String clavehashtext = new String(clavehash, StandardCharsets.ISO_8859_1);
		
		String desencriptado = AES.desencriptarAES(texto, clavehashtext, iv);
		
		return desencriptado;
	}
	
}
