package app.alertify.crypto;

import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.alertify.config.Global;

public class AES {

	private final static String secretKeySpecText = "AES";
	private final static String cipherInstanceText = "AES/GCM/NoPadding";
	private static final int TAG_LENGTH_BIT = 128;
	private static final int KEY_LENGTH = 32;
	private static final int IV_LENGTH = 12;
	
	private AES() {
		
	}
	
	public static int getIvLength() {
		return IV_LENGTH;
	}

	public static int getKeyLength() {
		return KEY_LENGTH;
	}

	public static byte[] encriptarAES(byte[] data, byte[] clave, byte[] iv) throws Exception {
        SecretKeySpec claveObject = new SecretKeySpec(clave, secretKeySpecText);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);

        Cipher cipher = Cipher.getInstance(cipherInstanceText);
        cipher.init(Cipher.ENCRYPT_MODE, claveObject, gcmSpec);

        return cipher.doFinal(data);
    }
	
	public static String encriptarAES(String texto, String claveTexto, String ivTexto) throws Exception {
		byte[] data = texto.getBytes(Global.getAppCharset());
		byte[] clave = claveTexto.getBytes(Global.getAppCharset());
		byte[] iv = ivTexto.getBytes(Global.getAppCharset());
		byte[] enc = encriptarAES(data, clave, iv);
		return Base64.getEncoder().encodeToString(enc);
	}

    public static byte[] desencriptarAES(byte[] data, byte[] clave, byte[] iv) throws Exception {
    	SecretKeySpec claveObject = new SecretKeySpec(clave, secretKeySpecText);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);

        Cipher cipher = Cipher.getInstance(cipherInstanceText);
        cipher.init(Cipher.DECRYPT_MODE, claveObject, gcmSpec);

        return cipher.doFinal(data);
    }

    public static String desencriptarAES(String textoEncriptado, String claveTexto, String ivTexto) throws Exception {
		byte[] data = Base64.getDecoder().decode(textoEncriptado);
		byte[] clave = claveTexto.getBytes(Global.getAppCharset());
		byte[] iv = ivTexto.getBytes(Global.getAppCharset());
		byte[] des = desencriptarAES(data, clave, iv);
    	return new String(des, Global.getAppCharset());
    }
}
