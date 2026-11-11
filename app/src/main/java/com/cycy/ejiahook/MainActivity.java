package com.cycy.ejiahook;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 配置界面。写入 SharedPreferences("config") 和 /sdcard/Download/ejiahook.json，
 * 被注入的 ejia 进程读文件拿配置，改完设置无需重启目标应用。
 */
public class MainActivity extends Activity {

    private static final int REQ_PHONE = 1001;
    private static final String PUBLIC_FILE_NAME = "config.json";

    private CheckBox cbLocation;
    private CheckBox cbDevice;
    private CheckBox cbSystemLocation;
    private EditText etLat;
    private EditText etLng;
    private EditText etRadius;
    private EditText etCity;
    private EditText etProvince;
    private EditText etDistrict;
    private EditText etStreet;
    private EditText etDetail;
    private EditText etDevice;
    private TextView tvDeviceSource;

    private SharedPreferences prefs;

    private final BroadcastReceiver captureReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Config.ACTION_CAPTURE.equals(intent.getAction())) {
                return;
            }
            double lat = intent.getDoubleExtra("lat", 0);
            double lng = intent.getDoubleExtra("lng", 0);
            String city = intent.getStringExtra("city");
            String province = intent.getStringExtra("province");
            String district = intent.getStringExtra("district");
            String street = intent.getStringExtra("street");
            String address = intent.getStringExtra("address");
            if (lat == 0 && lng == 0) {
                return;
            }
            etLat.setText(format(lat));
            etLng.setText(format(lng));
            if (!TextUtils.isEmpty(city)) {
                etCity.setText(city);
            }
            if (!TextUtils.isEmpty(province)) {
                etProvince.setText(province);
            }
            if (!TextUtils.isEmpty(district)) {
                etDistrict.setText(district);
            }
            if (!TextUtils.isEmpty(street)) {
                etStreet.setText(street);
            }
            // 完整地址减去已填的省市区街道，剩下的放进「详细地址」
            if (!TextUtils.isEmpty(address)) {
                etDetail.setText(remainder(address, province, city, district, street));
            }
            toast("已填入真实定位，可按需微调后保存");
            // 拿到就关掉回传，避免持续广播
            prefs.edit().putBoolean(Config.KEY_CAPTURE, false).apply();
            writeConfigFile(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);

        cbLocation = findViewById(R.id.cb_location);
        cbDevice = findViewById(R.id.cb_device);
        cbSystemLocation = findViewById(R.id.cb_system_location);
        etLat = findViewById(R.id.et_lat);
        etLng = findViewById(R.id.et_lng);
        etRadius = findViewById(R.id.et_radius);
        etCity = findViewById(R.id.et_city);
        etProvince = findViewById(R.id.et_province);
        etDistrict = findViewById(R.id.et_district);
        etStreet = findViewById(R.id.et_street);
        etDetail = findViewById(R.id.et_detail);
        etDevice = findViewById(R.id.et_device);
        tvDeviceSource = findViewById(R.id.tv_device_source);
        Button btnSave = findViewById(R.id.btn_save);
        Button btnCapture = findViewById(R.id.btn_capture);
        TextView tvTip = findViewById(R.id.tv_tip);

        load();
        btnSave.setOnClickListener(v -> save());
        btnCapture.setOnClickListener(v -> captureRealLocation());
        tvTip.setText(getString(R.string.tip));

        registerCaptureReceiver();

        if (!hasPhonePermission()) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQ_PHONE);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 从完整地址里剥掉省市区街道，剩下的当详细地址；剥不干净就返回空，避免重复拼接。 */
    private static String remainder(String address, String province, String city,
                                    String district, String street) {
        String prefix = safe(province) + safe(city) + safe(district) + safe(street);
        String text = address.startsWith("中国") ? address.substring(2) : address;
        if (!prefix.isEmpty() && text.startsWith(prefix)) {
            return text.substring(prefix.length()).trim();
        }
        if (!safe(street).isEmpty()) {
            int index = text.indexOf(street);
            if (index >= 0) {
                return text.substring(index + street.length()).trim();
            }
        }
        // 拼不上就留空，让用户自己决定要不要填
        return "";
    }

    private void registerCaptureReceiver() {
        IntentFilter filter = new IntentFilter(Config.ACTION_CAPTURE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(captureReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(captureReceiver);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDeviceField();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PHONE) {
            refreshDeviceField();
        }
    }

    private void load() {
        cbLocation.setChecked(prefs.getBoolean(Config.KEY_ENABLED, false));
        cbDevice.setChecked(prefs.getBoolean(Config.KEY_DEVICE_ENABLED, false));
        cbSystemLocation.setChecked(prefs.getBoolean(Config.KEY_SYSTEM_LOCATION, true));
        etLat.setText(prefs.getString(Config.KEY_LAT, ""));
        etLng.setText(prefs.getString(Config.KEY_LNG, ""));
        etRadius.setText(prefs.getString(Config.KEY_RADIUS, "10"));
        etCity.setText(prefs.getString(Config.KEY_CITY, ""));
        etProvince.setText(prefs.getString(Config.KEY_PROVINCE, ""));
        etDistrict.setText(prefs.getString(Config.KEY_DISTRICT, ""));
        etStreet.setText(prefs.getString(Config.KEY_STREET, ""));
        etDetail.setText(prefs.getString(Config.KEY_DETAIL, ""));
    }

    /**
     * 标识输入框：
     * 未启用覆盖时，永远显示真实值（每次回到界面都会重新读，改坏了也会恢复）；
     * 启用覆盖时，显示用户自己填的值。
     */
    private void refreshDeviceField() {
        if (cbDevice.isChecked()) {
            etDevice.setText(prefs.getString(Config.KEY_DEVICE_ID, ""));
            tvDeviceSource.setText(R.string.device_source_custom);
            return;
        }
        RealId real = readRealId();
        if (real != null) {
            etDevice.setText(real.value);
            tvDeviceSource.setText(getString(R.string.device_source_real, real.source));
        } else {
            etDevice.setText("");
            tvDeviceSource.setText(hasPhonePermission()
                    ? R.string.device_source_none
                    : R.string.device_source_grant);
        }
    }

    private static final class RealId {
        final String value;
        final String source;

        RealId(String value, String source) {
            this.value = value;
            this.source = source;
        }
    }

    private RealId readRealId() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm != null && hasPhonePermission()) {
            String imei = safeGet(tm, "imei");
            if (!TextUtils.isEmpty(imei)) {
                return new RealId(imei, "IMEI");
            }
            String meid = safeGet(tm, "meid");
            if (!TextUtils.isEmpty(meid)) {
                return new RealId(meid, "MEID");
            }
        }
        String androidId = Settings.Secure.getString(getContentResolver(), "android_id");
        if (!TextUtils.isEmpty(androidId)) {
            return new RealId(androidId, "ANDROID_ID");
        }
        return null;
    }

    private String safeGet(TelephonyManager tm, String kind) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return null;
            }
            return "imei".equals(kind) ? tm.getImei() : tm.getMeid();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean hasPhonePermission() {
        return checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 让 ejia 把真实定位回传过来：置位请求 → 拉起 ejia → 等广播。 */
    private void captureRealLocation() {
        if (cbLocation.isChecked()) {
            toast("请先取消勾选「启用定位伪造」，否则取到的是伪造值");
            return;
        }
        prefs.edit()
                .putBoolean(Config.KEY_CAPTURE, true)
                .putLong(Config.KEY_CAPTURE_TS, System.currentTimeMillis())
                .apply();
        writeConfigFile(true);

        Intent launch = null;
        try {
            launch = getPackageManager().getLaunchIntentForPackage("com.dcits.ehome");
        } catch (Throwable ignored) {
        }
        if (launch == null) {
            // 包可见性被限制时 getLaunchIntentForPackage 会返回 null，直接按组件拉起
            launch = new Intent();
            launch.setComponent(new android.content.ComponentName(
                    "com.dcits.ehome", "com.dcits.ehome.activity.SplashScreenActivity"));
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launch);
        } catch (Throwable t) {
            toast("打不开「神州e家」：" + t.getClass().getSimpleName());
            return;
        }
        toast("已打开神州e家，等它读取定位后回到这里");
    }

    private void save() {
        String latText = etLat.getText().toString().trim();
        String lngText = etLng.getText().toString().trim();
        String radiusText = etRadius.getText().toString().trim();

        boolean locationOn = cbLocation.isChecked();
        double lat = 0;
        double lng = 0;

        if (locationOn) {
            if (latText.isEmpty() || lngText.isEmpty()) {
                toast("启用定位伪造需要先填纬度和经度");
                return;
            }
            try {
                lat = Double.parseDouble(latText);
                lng = Double.parseDouble(lngText);
            } catch (NumberFormatException e) {
                toast("纬度/经度格式不对");
                return;
            }
            if (lat < -90 || lat > 90) {
                toast("纬度需要在 -90 ~ 90 之间");
                return;
            }
            if (lng < -180 || lng > 180) {
                toast("经度需要在 -180 ~ 180 之间");
                return;
            }
        }

        double radius = 10;
        if (!radiusText.isEmpty()) {
            try {
                radius = Double.parseDouble(radiusText);
            } catch (NumberFormatException e) {
                toast("漂移半径格式不对");
                return;
            }
            if (radius < 0) {
                toast("漂移半径不能为负数");
                return;
            }
        }

        boolean deviceOn = cbDevice.isChecked();
        String deviceId = etDevice.getText().toString().trim();
        if (deviceOn && TextUtils.isEmpty(deviceId)) {
            toast("启用标识覆盖需要填写标识值");
            return;
        }

        prefs.edit()
                .putBoolean(Config.KEY_ENABLED, locationOn)
                .putString(Config.KEY_LAT, locationOn ? format(lat) : "")
                .putString(Config.KEY_LNG, locationOn ? format(lng) : "")
                .putString(Config.KEY_RADIUS, format(radius))
                .putString(Config.KEY_CITY, etCity.getText().toString().trim())
                .putString(Config.KEY_PROVINCE, etProvince.getText().toString().trim())
                .putString(Config.KEY_DISTRICT, etDistrict.getText().toString().trim())
                .putString(Config.KEY_STREET, etStreet.getText().toString().trim())
                .putString(Config.KEY_DETAIL, etDetail.getText().toString().trim())
                .putBoolean(Config.KEY_SYSTEM_LOCATION, cbSystemLocation.isChecked())
                .putBoolean(Config.KEY_DEVICE_ENABLED, deviceOn)
                // 未启用覆盖时不保存显示出来的真实值，避免把真实标识写进配置
                .putString(Config.KEY_DEVICE_ID, deviceOn ? deviceId : "")
                .apply();

        writeConfigFile(false);
        toast("已保存，约 2 秒内生效");
    }

    /** 把当前界面上的配置写到 /sdcard/Download/ejiahook.json（被注入的 ejia 进程读它）。 */
    private void writeConfigFile(boolean capture) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        try {
            boolean locationOn = cbLocation.isChecked();
            JSONObject json = new JSONObject();
            json.put(Config.KEY_ENABLED, locationOn);
            json.put(Config.KEY_LAT, etLat.getText().toString().trim());
            json.put(Config.KEY_LNG, etLng.getText().toString().trim());
            json.put(Config.KEY_RADIUS, TextUtils.isEmpty(etRadius.getText().toString().trim())
                    ? "10" : etRadius.getText().toString().trim());
            json.put(Config.KEY_CITY, etCity.getText().toString().trim());
            json.put(Config.KEY_PROVINCE, etProvince.getText().toString().trim());
            json.put(Config.KEY_DISTRICT, etDistrict.getText().toString().trim());
            json.put(Config.KEY_STREET, etStreet.getText().toString().trim());
            json.put(Config.KEY_DETAIL, etDetail.getText().toString().trim());
            boolean deviceOn = cbDevice.isChecked();
            json.put(Config.KEY_SYSTEM_LOCATION, cbSystemLocation.isChecked());
            json.put(Config.KEY_DEVICE_ENABLED, deviceOn);
            json.put(Config.KEY_DEVICE_ID, deviceOn ? etDevice.getText().toString().trim() : "");
            // 调试开关由 root 侧控制，UI 保存时保留原值
            json.put(Config.KEY_DEBUG, readDebugFlag());
            json.put(Config.KEY_CAPTURE, capture);
            json.put(Config.KEY_CAPTURE_TS, capture ? System.currentTimeMillis() : 0);
            writePublicFile(json.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    private void writePublicFile(byte[] data) {
        try {
            ContentResolver cr = getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            long id = findIdInSubdir(cr, collection);
            if (id >= 0) {
                Uri uri = ContentUris.withAppendedId(collection, id);
                try (OutputStream out = cr.openOutputStream(uri, "wt")) {
                    if (out != null) {
                        out.write(data);
                    }
                }
            } else {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, PUBLIC_FILE_NAME);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath());
                Uri uri = cr.insert(collection, values);
                if (uri != null) {
                    try (OutputStream out = cr.openOutputStream(uri)) {
                        if (out != null) {
                            out.write(data);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String relativePath() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + Config.DOWNLOAD_SUBDIR + "/";
    }

    /** 只找 Download/ejiahook/ 里的配置文件；写的时候必须用它，避免又写回旧文件。 */
    private long findIdInSubdir(ContentResolver cr, Uri collection) {
        return findId(cr, collection, true);
    }

    /** 读的时候宽松一点：先找新目录，找不到再认旧版放在 Download 根目录的。 */
    private long findPublicFileId(ContentResolver cr, Uri collection) {
        long id = findId(cr, collection, true);
        return id >= 0 ? id : findId(cr, collection, false);
    }

    private long findId(ContentResolver cr, Uri collection, boolean requireSubdir) {
        try (Cursor cursor = cr.query(collection,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH},
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{PUBLIC_FILE_NAME}, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String path = cursor.getString(1);
                    long id = cursor.getLong(0);
                    boolean inSubdir = path != null && path.contains(Config.DOWNLOAD_SUBDIR);
                    if (inSubdir == requireSubdir) {
                        return id;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private boolean readDebugFlag() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        try {
            ContentResolver cr = getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            long id = findPublicFileId(cr, collection);
            if (id < 0) {
                return false;
            }
            try (InputStream in = cr.openInputStream(ContentUris.withAppendedId(collection, id))) {
                if (in == null) {
                    return false;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, n);
                }
                return new JSONObject(new String(buffer.toByteArray(), "UTF-8"))
                        .optBoolean(Config.KEY_DEBUG, false);
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String format(double value) {
        return String.valueOf(value);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
