package app.alertify.crypto;

import java.util.Objects;

public class CryptoMessage {
	private final String message;
	private final String iv;
	
	public CryptoMessage(String message, String iv) {
		this.message = message;
		this.iv = iv;
	}
	
	public String getMessage() {
		return message;
	}
	
	public String getIv() {
		return iv;
	}

	@Override
	public int hashCode() {
		return Objects.hash(iv, message);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CryptoMessage other = (CryptoMessage) obj;
		return Objects.equals(iv, other.iv) && Objects.equals(message, other.message);
	}

	@Override
	public String toString() {
		return "CryptoMessage [message=" + message + ", iv=" + iv + "]";
	}
}
