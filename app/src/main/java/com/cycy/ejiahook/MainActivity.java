package com.cycy.ejiahook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 配置界面。写入 SharedPreferences("config")，被注入的 ejia 进程通过
 * ConfigProvider 读取，改完设置无需重启目标应用。
 */
public class MainActivity extends Activity {

    private CheckBox cbLocation;
    private CheckBox cbDevice;
    private EditText etLat;
    private EditText etLng;
    private EditText etRadius;
    private EditText etCity;
    private EditText etAddress;
    private EditText etDevice;

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
        Button btnSave = findViewById(R.id.btn_save);
        TextView tvTip = findViewById(R.id.tv_tip);

        load();
        btnSave.setOnClickListener(v -> save());
        tvTip.setText(getString(R.string.tip));
    }

    private void load() {
        cbLocation.setChecked(prefs.getBoolean(Config.KEY_ENABLED, false));
        cbDevice.setChecked(prefs.getBoolean(Config.KEY_DEVICE_ENABLED, false));
        etLat.setText(prefs.getString(Config.KEY_LAT, ""));
        etLng.setText(prefs.getString(Config.KEY_LNG, ""));
        etRadius.setText(prefs.getString(Config.KEY_RADIUS, "5"));
        etCity.setText(prefs.getString(Config.KEY_CITY, ""));
        etAddress.setText(prefs.getString(Config.KEY_ADDRESS, ""));
        etDevice.setText(prefs.getString(Config.KEY_DEVICE_ID, ""));
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
                .putString(Config.KEY_DEVICE_ID, deviceOn ? deviceId : "")
                .apply();

        toast("已保存，约 2 秒内生效");
    }

    private static String format(double value) {
        return String.valueOf(value);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
