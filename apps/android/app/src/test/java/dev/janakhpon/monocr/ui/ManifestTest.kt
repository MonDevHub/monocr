package dev.janakhpon.monocr.ui

import android.Manifest
import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
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
}
