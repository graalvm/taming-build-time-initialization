package org.graalvm;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

public class SecurityProblems {
    // Will leak the user's home directory of the user building the image!
    private static final String USER_HOME = System.getProperty("user.home");

    // Will "bake" the private key found during the image build into the image!
    private static final PrivateKey runtimeSuppliedPrivateKey = loadPrivateKey();

    // Will always contain the same random seed at image runtime!
    private static final SimplePRNG randomNumberGenerator = new SimplePRNG(System.currentTimeMillis());

    public static void main(String[] args) {
        System.out.println("My user home is: " + USER_HOME);

        System.out.println("My private key hash is: " + Arrays.hashCode(runtimeSuppliedPrivateKey.getEncoded()));

        System.out.println("An entirely random sequence: " + randomNumberGenerator.nextRandom() + " " + randomNumberGenerator.nextRandom() + " " + randomNumberGenerator.nextRandom());
    }

    private static PrivateKey loadPrivateKey() {
        try {
            InputStream privateKeyStream = SecurityProblems.class.getResourceAsStream("/private-key.der");
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(privateKeyStream.readAllBytes());
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            e.printStackTrace();
            return null;
        }
    }
}

class SimplePRNG {

    private static final long PRIME = 7937;
    private long seed;

    SimplePRNG(long seed) {
        this.seed = seed % PRIME;
    }

    long nextRandom() {
        seed = (seed * 2) % PRIME;
        return seed;
    }

}
