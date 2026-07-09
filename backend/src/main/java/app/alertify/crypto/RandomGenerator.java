package app.alertify.crypto;

import java.security.SecureRandom;
import java.util.Base64;

public class RandomGenerator {
	
	private RandomGenerator() {
		
	}

	public static byte[] generarBytesAleatorios(int numBytes) {
		try {
            SecureRandom secureRandom = new SecureRandom();

            byte[] bytesAleatorios = new byte[numBytes];
            secureRandom.nextBytes(bytesAleatorios);

            return bytesAleatorios;
        } catch (Exception e) {
            throw new RuntimeException("Error al generar los bytes aleatorios", e);
        }
	}
	
	public static String generarTextoAleatorio(int numBytes) {
        try {
            byte[] bytesAleatorios = generarBytesAleatorios(numBytes);
            
            String base64Text = Base64.getEncoder().encodeToString(bytesAleatorios);
            
            return base64Text.substring(0, base64Text.length() > numBytes ? numBytes : base64Text.length());
        } catch (Exception e) {
            throw new RuntimeException("Error al generar texto aleatorio", e);
        }
    }
}
