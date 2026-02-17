package app.alertify.crypto;

import java.security.MessageDigest;

import app.alertify.config.Global;

public class SHA256 {
	
	private SHA256() {
		
	}

	public static byte[] hashSHA256(String text) throws Exception {
		if(text == null) {
			text = "";
		}
		
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        
        return digest.digest(text.getBytes(Global.getAppCharset()));
    }
}
