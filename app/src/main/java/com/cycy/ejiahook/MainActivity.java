package com.cycy.ejiahook;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
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
import android.view.View;
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
 * 配置界面。写入 SharedPreferences("config")，被注入的 ejia 进程通过
 * remote prefs / ConfigProvider / 调试文件读取，改完设置无需重启目标应用。
 */
public class MainActivity extends Activity {

    private static final int REQ_PHONE = 1001;

    private CheckBox cbLocation;
    private CheckBox cbDevice;
    private EditText etLat;
    private EditText etLng;
    private EditText etRadius;
    private EditText etCity;
    private EditText etAddress;
    private EditText etDevice;
    private TextView tvDeviceSource;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);

        cbLocation = findViewById(R.id.cb_location);
        cbDevice = findViewById(R.id.cb_device);
        etLat = findViewById(R.id.et_lat);
        etLng = findViewById(R.id.et_lng);
        etRadius = findViewById(R.id.et_radius);
        etCity = findViewById(R.id.et_city);
        etAddress = findViewById(R.id.et_address);
        etDevice = findViewById(R.id.et_device);
        tvDeviceSource = findViewById(R.id.tv_device_source);
        Button btnSave = findViewById(R.id.btn_save);
        TextView tvTip = findViewById(R.id.tv_tip);

        load();
        btnSave.setOnClickListener(v -> save());
        tvTip.setText(getString(R.string.tip));

        if (!hasPhonePermission()) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQ_PHONE);
        }
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
        etLat.setText(prefs.getString(Config.KEY_LAT, ""));
        etLng.setText(prefs.getString(Config.KEY_LNG, ""));
        etRadius.setText(prefs.getString(Config.KEY_RADIUS, "5"));
        etCity.setText(prefs.getString(Config.KEY_CITY, ""));
        etAddress.setText(prefs.getString(Config.KEY_ADDRESS, ""));
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

        double radius = 5;
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
                .putString(Config.KEY_ADDRESS, etAddress.getText().toString().trim())
                .putBoolean(Config.KEY_DEVICE_ENABLED, deviceOn)
                // 未启用覆盖时不保存显示出来的真实值，避免把真实标识写进配置
                .putString(Config.KEY_DEVICE_ID, deviceOn ? deviceId : "")
                .apply();

        // 同时落一份到 /sdcard/Download/ejiahook.json：
        // 被注入的 ejia 进程读这个文件最稳（Provider 在部分 ROM 上会被拦）
        publishConfigFile(locationOn, lat, lng, radius,
                etCity.getText().toString().trim(),
                etAddress.getText().toString().trim(),
                deviceOn, deviceId);

        toast("已保存，约 2 秒内生效");
    }

    private static final String PUBLIC_FILE_NAME = "ejiahook.json";

    private void publishConfigFile(boolean locationOn, double lat, double lng, double radius,
                                   String city, String address, boolean deviceOn, String deviceId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put(Config.KEY_ENABLED, locationOn);
            json.put(Config.KEY_LAT, locationOn ? format(lat) : "");
            json.put(Config.KEY_LNG, locationOn ? format(lng) : "");
            json.put(Config.KEY_RADIUS, format(radius));
            json.put(Config.KEY_CITY, city);
            json.put(Config.KEY_ADDRESS, address);
            json.put(Config.KEY_DEVICE_ENABLED, deviceOn);
            json.put(Config.KEY_DEVICE_ID, deviceOn ? deviceId : "");
            // 保留调试开关（调试时用 root 改文件，别被 UI 覆盖掉）
            json.put(Config.KEY_DEBUG, readDebugFlag());
            byte[] data = json.toString().getBytes("UTF-8");

            ContentResolver cr = getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            long id = findPublicFileId(cr, collection);
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
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
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

    private long findPublicFileId(ContentResolver cr, Uri collection) {
        try (Cursor cursor = cr.query(collection,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{PUBLIC_FILE_NAME}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
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
