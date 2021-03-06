package danvim.qrsign.utils

import android.util.Log
import com.google.crypto.tink.subtle.Base64
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import danvim.qrsign.exceptions.InvalidMessageFormatException
import danvim.qrsign.exceptions.PublicKeyNotFoundException
import org.jsoup.Jsoup
import java.lang.Exception
import java.lang.NullPointerException
import java.lang.RuntimeException
import java.net.URL
import java.security.GeneralSecurityException

open class Validator(
    private val pageReader: PageReaderInterface = PageReader()
) {
    fun getMessage(content: String): SignedMessage {
        val items = content.split('\n')
        if (items.size != 4) {
            throw InvalidMessageFormatException("SignedMessage item length incorrect.")
        }
        val (name, date, publicKeyLocation, signature) = items
        if (!date.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
            throw InvalidMessageFormatException("Date format must be YYYY-MM-DD.")
        }
        val keyType = when {
            publicKeyLocation.startsWith("http") -> KeyType.URL
            publicKeyLocation.startsWith("FB:") -> KeyType.FACEBOOK
            else -> throw InvalidMessageFormatException("Key type invalid.")
        }
        if (signature.length != 88) {
            throw InvalidMessageFormatException("Signature must be length of 88.")
        }
        return SignedMessage(
            Message(name, date, keyType, publicKeyLocation),
            signature
        )
    }

    fun validateKeyPair(
        publicKey: String,
        privateKey: String
    ): Boolean {
        try {
            val signature = Ed25519Sign(Base64.decode(privateKey)).sign(byteArrayOf())
            Ed25519Verify(Base64.decode(publicKey)).verify(signature, byteArrayOf())
        } catch (e: Exception) {
            return false
        }
        return true
    }

    fun validateMessage(
        signedMessage: SignedMessage,
        publicKey: String,
        isVerified: Boolean?
    ): ValidationResult {
        try {
            Ed25519Verify(Base64.decode(publicKey)).verify(
                Base64.decode(signedMessage.signature),
                signedMessage.message.validator.toByteArray()
            )
        } catch (e: GeneralSecurityException) {
            return ValidationResult(false, isVerified = false)
        }
        return ValidationResult(true, isVerified)
    }

    /**
     * Fetches public key from specified public key location in signedMessage
     * @param signedMessage The signedMessage extracted from the raw result
     * @return The ED25519 public key string from target location
     * @throws PublicKeyNotFoundException
     */
    fun scrapePublicKey(signedMessage: SignedMessage): ScrapeResult {
        return if (signedMessage.message.keyType == KeyType.FACEBOOK)
            scrapeFacebookAbout(signedMessage.message.publicKeyLocation.substring(3))
        else
            scrapePage(signedMessage.message.publicKeyLocation)
    }

    /**
     * Extracts public key from about page
     * @param pageId
     * @return The supposed public key
     */
    private fun scrapeFacebookAbout(pageId: String): ScrapeResult {
        try {
            val content = pageReader.readPage(URL("https://www.facebook.com/$pageId/about"))
            return ScrapeResult(
                Regex(PUBLIC_KEY_REGEX).find(content)!!.groupValues[1],
                content,
                content.contains(FACEBOOK_VERIFIED_STRING)
            )
        } catch (e: RuntimeException /* MalformedURLException IOException NullPointerException*/) {
            Log.e("QR Sign", e.toString())
            throw PublicKeyNotFoundException()
        }
    }

    /**
     * Extracts public key from meta tag with content "qr-sign"
     * @param url
     * @return The supposed public key
     */
    private fun scrapePage(url: String): ScrapeResult {
        try {
            val content = pageReader.readPage(URL(url))
            val dom = Jsoup.parse(content)
            return ScrapeResult(
                dom.head().selectFirst("meta[name='qr-sign']")!!.attr("content"),
                content
            )
        } catch (e: NullPointerException) {
            Log.e("QR Sign", e.toString())
            throw PublicKeyNotFoundException()
        }
    }

    data class ValidationResult(
        val isWellSigned: Boolean,
        val isVerified: Boolean?
    )

    data class ScrapeResult(
        val key: String,
        val pageContent: String,
        var isVerified: Boolean? = null
    )

    companion object {
        const val PUBLIC_KEY_REGEX = "QRSign&lt;([^&;\\s]{44})&gt;"
        const val FACEBOOK_VERIFIED_STRING = "\\u003Cspan>A blue verification badge confirms that this is an authentic Page for this public figure, media company or brand.\\u003C/span>\n"
    }
}