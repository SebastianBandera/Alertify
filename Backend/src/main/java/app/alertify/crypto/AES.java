package app.alertify.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class AES {
	
	private AES() {
		
	}
	
	public static String encriptarAES(String texto, String claveTexto, String ivTexto) throws Exception {
        SecretKeySpec clave = new SecretKeySpec(claveTexto.getBytes(StandardCharsets.ISO_8859_1), "AES");
        IvParameterSpec iv = new IvParameterSpec(ivTexto.getBytes(StandardCharsets.ISO_8859_1));

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, clave, iv);

        byte[] textoEncriptado = cipher.doFinal(texto.getBytes(StandardCharsets.ISO_8859_1));

        return Base64.getEncoder().encodeToString(textoEncriptado);
    }

    public static String desencriptarAES(String textoEncriptado, String claveTexto, String ivTexto) throws Exception {
        SecretKeySpec clave = new SecretKeySpec(claveTexto.getBytes(StandardCharsets.ISO_8859_1), "AES");
        IvParameterSpec iv = new IvParameterSpec(ivTexto.getBytes(StandardCharsets.ISO_8859_1));

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, clave, iv);

        byte[] textoDesencriptado = cipher.doFinal(Base64.getDecoder().decode(textoEncriptado));

        return new String(textoDesencriptado, StandardCharsets.ISO_8859_1);
    }
}
