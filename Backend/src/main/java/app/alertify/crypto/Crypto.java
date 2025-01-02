package app.alertify.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

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
	
	public static String desencriptar(String textoConIv, String claveTexto) throws Exception {
		CryptoMessage desempaquetadoIV = desempaquetarIV(textoConIv);
		
		String iv = desempaquetadoIV.getIv();
		String texto = desempaquetadoIV.getMessage();
		
		return desencriptar(texto, claveTexto, iv);
	}
	
	public static String empaquetarIV(String texto, String iv) throws Exception {
		Objects.nonNull(iv);
		Objects.nonNull(texto);
		
		if(iv.length() != 16) {
			throw new Exception("Se esperaba una cantidad de caracteres de 16 exactos para el iv");
		}
		
		String newBody = iv + "$" + texto;
		
		return newBody;
	}
	
	public static CryptoMessage desempaquetarIV(String texto) throws Exception {
		Objects.nonNull(texto);
		
		if(texto.length() < 16) {
			throw new Exception("Se esperaba una cantidad de caracteres mayor");
		}
		
		String iv = texto.substring(0, 16);
		String mensaje = texto.substring(17);
		
		return new CryptoMessage(mensaje, iv);
	}
	
}
