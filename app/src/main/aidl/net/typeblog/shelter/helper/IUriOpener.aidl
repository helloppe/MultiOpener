// IUriOpener.aidl
package net.typeblog.shelter.helper;

import android.os.ParcelFileDescriptor;

interface IUriOpener {
    ParcelFileDescriptor openFile(in String mode);
}
