package com.situationship.app.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situationship.app.data.model.ProfileUpdate
import com.situationship.app.data.model.PublicProfile
import com.situationship.app.data.remote.ApiService
import com.situationship.app.data.remote.userMessage
import com.situationship.app.data.repo.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.max

data class ProfileForm(
    val name: String = "",
    val gender: String? = null,
    val interestedIn: String? = null,
    val bio: String = "",
    val city: String = "",
    val minAge: Int = 18,
    val maxAge: Int = 60,
)

data class ProfileUi(
    val form: ProfileForm = ProfileForm(),
    val photos: List<String> = emptyList(),
    val uploading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val blocked: List<PublicProfile>? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val api: ApiService,
    private val session: SessionRepository,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {
    val user = session.user
    private val _ui = MutableStateFlow(ProfileUi())
    val ui = _ui.asStateFlow()

    init {
        session.user.value?.let { u ->
            _ui.value = ProfileUi(
                form = ProfileForm(u.name.orEmpty(), u.gender, u.interestedIn, u.bio, u.city, u.minAge, u.maxAge),
                photos = u.photos,
            )
        }
    }

    fun edit(f: (ProfileForm) -> ProfileForm) = _ui.update { it.copy(form = f(it.form), error = null) }

    fun setGender(g: String) = edit { f ->
        // first time: default "show me" to the opposite gender
        f.copy(gender = g, interestedIn = f.interestedIn ?: if (g == "male") "female" else "male")
    }

    fun addPhoto(uri: Uri) = viewModelScope.launch {
        _ui.update { it.copy(uploading = true, error = null) }
        try {
            val bytes = withContext(Dispatchers.IO) { compress(uri) }
            val part = MultipartBody.Part.createFormData("photo", "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
            val u = api.uploadPhoto(part).user
            session.setUser(u)
            _ui.update { it.copy(photos = u.photos) }
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.userMessage()) }
        } finally {
            _ui.update { it.copy(uploading = false) }
        }
    }

    fun deletePhoto(index: Int) = viewModelScope.launch {
        try {
            val u = api.deletePhoto(index).user
            session.setUser(u)
            _ui.update { it.copy(photos = u.photos) }
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.userMessage()) }
        }
    }

    fun save() {
        val f = _ui.value.form
        val err = when {
            _ui.value.photos.isEmpty() -> "Add at least one photo"
            f.name.trim().length < 2 -> "Please enter your name (min 2 characters)"
            f.gender == null -> "Please select your gender"
            f.interestedIn == null -> "Please select who you want to meet"
            else -> null
        }
        if (err != null) { _ui.update { it.copy(error = err) }; return }
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = null) }
            try {
                val u = api.updateProfile(
                    ProfileUpdate(f.name.trim(), f.gender!!, f.interestedIn!!, f.bio.trim(), f.city.trim(), f.minAge, max(f.minAge, f.maxAge))
                ).user
                session.setUser(u) // ACTIVE -> RouteGuard opens Discover during setup
                _ui.update { it.copy(saved = true) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.userMessage()) }
            } finally {
                _ui.update { it.copy(saving = false) }
            }
        }
    }

    fun loadBlocked() = viewModelScope.launch {
        runCatching { api.blocked().users }.onSuccess { list -> _ui.update { it.copy(blocked = list) } }
    }

    fun unblock(id: String) = viewModelScope.launch {
        runCatching { api.unblock(id) }.onSuccess { _ui.update { s -> s.copy(blocked = s.blocked?.filterNot { it.id == id }) } }
    }

    fun logout() = viewModelScope.launch { session.logout() }

    fun deleteAccount() = viewModelScope.launch {
        try { session.deleteAccount() } catch (e: Exception) { _ui.update { it.copy(error = e.userMessage()) } }
    }

    /** Downscale to max 1440px, fix rotation, re-encode as JPEG (also drops EXIF/GPS metadata). */
    private fun compress(uri: Uri): ByteArray {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1440) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("Cannot read image")
        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, 1440f / max(bmp.width, bmp.height))
        val m = Matrix().apply { postRotate(rotation); postScale(scale, scale) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return ByteArrayOutputStream().use { os ->
            out.compress(Bitmap.CompressFormat.JPEG, 85, os)
            os.toByteArray()
        }
    }
}
