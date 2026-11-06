package app.kolay.app.doc;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;

/**
 * Picking pictures from the gallery: the system Photo Picker where the platform has it (Android 13+, or Android 11/12 with
 * the picker update), otherwise the document picker for images. No storage permission either way.
 */
public final class PhotoPick {
    private PhotoPick() {}

    /** Intent for {@code startActivityForResult}; {@code multiple} allows several pictures (up to the picker's limit). */
    public static Intent intent(boolean multiple) {
        if (Build.VERSION.SDK_INT >= 33 || (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2)) {
            Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            if (multiple) {
                int max = MediaStore.getPickImagesMaxLimit();
                if (max > 1) i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max);
            }
            return i;
        }
        return new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
    }

    /** The picked pictures, in order; empty when nothing was picked. */
    public static List<Uri> uris(Intent data) {
        List<Uri> out = new ArrayList<>();
        if (data == null) return out;
        ClipData cd = data.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) { Uri u = cd.getItemAt(i).getUri(); if (u != null) out.add(u); }
        else if (data.getData() != null) out.add(data.getData());
        return out;
    }
}
