package dev.janakhpon.monocr.ui

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ManifestTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test fun `camera permission is not requested`() {
        // Declaring CAMERA makes ACTION_IMAGE_CAPTURE throw until it is granted, so a
        // denial left the Camera button dead. TakePicture needs no permission at all.
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertFalse(requested.contains(Manifest.permission.CAMERA))
    }

    @Test fun `the in-app language is stored on Android 12 and below`() {
        // AppCompat keeps the per-app locale itself below API 33 only when this
        // service carries autoStoreLocales; without it the choice was lost on restart.
        val service = app.packageManager.getServiceInfo(
            ComponentName(app, "androidx.appcompat.app.AppLocalesMetadataHolderService"),
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
        )
        assertTrue(service.metaData?.getBoolean("autoStoreLocales") == true)
    }
}
