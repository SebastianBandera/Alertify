package app.alertify.crypto;

import java.util.Objects;

public class Crypto {

	private Crypto() {
		
	}
	
	public static CryptoMessage encriptar(String texto, String claveTexto) throws Exception {
		if (texto == null || claveTexto == null) throw new Exception(new NullPointerException("Entrada nula al encriptar"));
		
		String iv = RandomGenerator.generarTextoAleatorio(AES.getIvLength());
		
		String encriptado = AES.encriptarAES(texto, claveTexto, iv);
		
		return new CryptoMessage(encriptado, iv);
	}
	

	public static String desencriptar(String texto, String claveTexto, String iv) throws Exception {
		return AES.desencriptarAES(texto, claveTexto, iv);
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
		
		if(iv.length() != AES.getIvLength()) {
			throw new Exception("Se esperaba una cantidad de caracteres de " + AES.getIvLength() + " exactos para el iv");
		}
		
		String newBody = iv + "$" + texto;
		
		return newBody;
	}
	
	public static CryptoMessage desempaquetarIV(String texto) throws Exception {
		Objects.nonNull(texto);
		
		if(texto.length() < AES.getIvLength()) {
			throw new Exception("Se esperaba una cantidad de caracteres mayor");
		}
		
		String iv = texto.substring(0, AES.getIvLength());
		String mensaje = texto.substring(AES.getIvLength() + 1);
		
		return new CryptoMessage(mensaje, iv);
	}
	
}
