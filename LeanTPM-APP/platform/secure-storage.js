const RAW_PREFIX = 'leantpm_secure_'
const KEY_ALIAS = 'LeanTPM.Mobile.Storage.v1'

function rawKey(key) { return `${RAW_PREFIX}${key}` }

function androidCrypto() {
	if (typeof globalThis.plus === 'undefined') return null
	// #ifdef APP-PLUS
	if (plus.os.name !== 'Android') return null
	try {
		const KeyStore = plus.android.importClass('java.security.KeyStore')
		const KeyGenerator = plus.android.importClass('javax.crypto.KeyGenerator')
		const Cipher = plus.android.importClass('javax.crypto.Cipher')
		const Builder = plus.android.importClass('android.security.keystore.KeyGenParameterSpec$Builder')
		const KeyProperties = plus.android.importClass('android.security.keystore.KeyProperties')
		const GCMParameterSpec = plus.android.importClass('javax.crypto.spec.GCMParameterSpec')
		const Base64 = plus.android.importClass('android.util.Base64')
		const JavaString = plus.android.importClass('java.lang.String')
		const store = KeyStore.getInstance('AndroidKeyStore')
		store.load(null)
		if (!store.containsAlias(KEY_ALIAS)) {
			const generator = KeyGenerator.getInstance('AES', 'AndroidKeyStore')
			const builder = new Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
			builder.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
			builder.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
			generator.init(builder.build())
			generator.generateKey()
		}
		const key = store.getKey(KEY_ALIAS, null)
		return {
			encrypt(value) {
				const cipher = Cipher.getInstance('AES/GCM/NoPadding')
				cipher.init(Cipher.ENCRYPT_MODE, key)
				const bytes = new JavaString(value).getBytes('UTF-8')
				return JSON.stringify({
					iv: Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP),
					data: Base64.encodeToString(cipher.doFinal(bytes), Base64.NO_WRAP)
				})
			},
			decrypt(payload) {
				const value = JSON.parse(payload)
				const cipher = Cipher.getInstance('AES/GCM/NoPadding')
				const iv = Base64.decode(value.iv, Base64.NO_WRAP)
				cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv))
				return new JavaString(cipher.doFinal(Base64.decode(value.data, Base64.NO_WRAP)), 'UTF-8').toString()
			}
		}
	} catch { return null }
	// #endif
	// #ifndef APP-PLUS
	return null
	// #endif
}

export function secureSet(key, value) {
	const serialized = JSON.stringify(value)
	const crypto = androidCrypto()
	uni.setStorageSync(rawKey(key), crypto ? `K1:${crypto.encrypt(serialized)}` : `S1:${serialized}`)
}

export function secureGet(key, fallback = null) {
	try {
		const stored = uni.getStorageSync(rawKey(key))
		if (!stored) return fallback
		if (String(stored).startsWith('K1:')) {
			const crypto = androidCrypto()
			if (!crypto) return fallback
			return JSON.parse(crypto.decrypt(String(stored).slice(3)))
		}
		if (String(stored).startsWith('S1:')) return JSON.parse(String(stored).slice(3))
		return fallback
	} catch { return fallback }
}

export function secureRemove(key) {
	try { uni.removeStorageSync(rawKey(key)) } catch { /* partially initialized runtime */ }
}
