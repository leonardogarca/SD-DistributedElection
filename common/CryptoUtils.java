package common;

import java.security.*;
import java.util.Base64;
import java.util.Map;

public class CryptoUtils {
    private static final String SIGNING_ALGORITHM = "SHA256withRSA";

    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    public static String sign(BuData bu, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance(SIGNING_ALGORITHM);
        signature.initSign(privateKey);
        signature.update(bu.toMessage().getBytes());
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    public static boolean verify(BuData bu) throws Exception {
        Signature signature = Signature.getInstance(SIGNING_ALGORITHM);
        PublicKey publicKey = bu.publicKey;
        signature.initVerify(publicKey);
        signature.update(bu.toMessage().getBytes());
        return signature.verify(Base64.getDecoder().decode(bu.signature));
    }
}