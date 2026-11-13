package com.libretv.client;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把日志文件以 content:// 附件形式分享出去。
 *
 * Android 7.0+ 禁止在 Intent 里带 file:// URI（FileUriExposedException），
 * 而本项目刻意不依赖 androidx，所以不用 FileProvider，改用一个极简
 * ContentProvider：固定读应用私有目录 files/share/libretv-log-latest.txt。
 * 分享前由 MainActivity 覆写该文件，接收方通过 grantUriPermissions 读取。
 */
public class LogProvider extends ContentProvider {

    public static final String AUTHORITY = "com.libretv.client.logshare";
    public static final String FILE_NAME = "libretv-log-latest.txt";
    public static final Uri SHARE_URI = Uri.parse("content://" + AUTHORITY + "/" + FILE_NAME);

    private File shareFile() {
        File d = new File(getContext().getFilesDir(), "share");
        if (!d.exists()) d.mkdirs();
        return new File(d, FILE_NAME);
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = shareFile();
        if (!f.isFile()) throw new FileNotFoundException("日志尚未生成");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 让微信/邮件等接收方能拿到文件名与大小，而不是一个无名附件。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = shareFile();
        MatrixCursor c = new MatrixCursor(new String[]{"_display_name", "_size"});
        c.addRow(new Object[]{f.getName(), f.isFile() ? f.length() : 0L});
        return c;
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
