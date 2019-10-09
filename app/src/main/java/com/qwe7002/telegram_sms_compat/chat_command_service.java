package com.qwe7002.telegram_sms_compat;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


public class chat_command_service extends Service {
    private static long offset = 0;
    private static int magnification = 1;
    private static int error_magnification = 1;
    private String chat_id;
    private String bot_token;
    private Context context;
    private OkHttpClient okhttp_client;
    private stop_broadcast_receiver stop_broadcast_receiver = null;
    private PowerManager.WakeLock wakelock;
    private WifiManager.WifiLock wifiLock;
    private int send_sms_status = -1;
    private String send_to_temp;
    private String bot_username = "";
    private final String log_tag = "chat_command_service";
    static Thread thread_main;
    private network_changed_receiver network_changed_receiver;
    private boolean have_bot_username = false;
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = public_func.get_notification_obj(getApplicationContext(), getString(R.string.chat_command_service_name));
        startForeground(2, notification);
        return START_STICKY;

    }

    @SuppressLint({"InvalidWakeLockTag", "WakelockTimeout"})
    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();

        SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);
        chat_id = sharedPreferences.getString("chat_id", "");
        bot_token = sharedPreferences.getString("bot_token", "");
        okhttp_client = public_func.get_okhttp_obj(sharedPreferences.getBoolean("doh_switch", true));

        wifiLock = ((WifiManager) Objects.requireNonNull(context.getApplicationContext().getSystemService(Context.WIFI_SERVICE))).createWifiLock(WifiManager.WIFI_MODE_FULL, "bot_command_polling_wifi");
        wakelock = ((PowerManager) Objects.requireNonNull(context.getSystemService(Context.POWER_SERVICE))).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "bot_command_polling");
        wifiLock.setReferenceCounted(false);
        wakelock.setReferenceCounted(false);
        if(!wifiLock.isHeld()){
            wifiLock.acquire();
        }
        if (!wakelock.isHeld()) {
            wakelock.acquire();
        }
        thread_main = new Thread(new thread_handle());
        thread_main.start();
        IntentFilter intentFilter = new IntentFilter(public_func.broadcast_stop_service);
        stop_broadcast_receiver = new stop_broadcast_receiver();
        registerReceiver(stop_broadcast_receiver, intentFilter);
        network_changed_receiver = new network_changed_receiver();
        intentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(network_changed_receiver, intentFilter);

    }

    @Override
    public void onDestroy() {
        wifiLock.release();
        wakelock.release();
        unregisterReceiver(stop_broadcast_receiver);
        unregisterReceiver(network_changed_receiver);
        stopForeground(true);
        super.onDestroy();
    }

    private void receive_handle(JsonObject result_obj) {
        String message_type = "";
        long update_id = result_obj.get("update_id").getAsLong();
        offset = update_id + 1;
        final message_json request_body = new message_json();
        request_body.chat_id = chat_id;
        JsonObject message_obj = null;
        if (result_obj.has("message")) {
            message_obj = result_obj.get("message").getAsJsonObject();
            message_type = message_obj.get("chat").getAsJsonObject().get("type").getAsString();
        }
        if (result_obj.has("channel_post")) {
            message_type = "channel";
            message_obj = result_obj.get("channel_post").getAsJsonObject();
        }
        if (message_obj == null) {
            //Reject group request
            public_func.write_log(context, "Request type is not allowed by security policy.");
            return;
        }
        JsonObject from_obj = null;
        boolean message_type_is_group = message_type.contains("group");
        if (message_type_is_group && !have_bot_username) {
            Log.i(log_tag, "receive_handle: Did not successfully get bot_username.");
            get_me();
        }
        if (message_obj.has("from")) {
            from_obj = message_obj.get("from").getAsJsonObject();
            if (message_type_is_group && from_obj.get("is_bot").getAsBoolean()) {
                Log.i(log_tag, "receive_handle: receive from bot.");
                return;
            }
        }
        if (message_obj.has("chat")) {
            from_obj = message_obj.get("chat").getAsJsonObject();
        }

        assert from_obj != null;
        String from_id = from_obj.get("id").getAsString();
        if (!chat_id.equals(from_id)) {
            public_func.write_log(context, "Chat ID[" + from_id + "] not allow");
            return;
        }

        String command = "";
        String command_bot_username = "";
        String request_msg = "";
        if (message_obj.has("text")) {
            request_msg = message_obj.get("text").getAsString();
        }
        if (message_obj.has("reply_to_message")) {
            JsonObject reply_obj = message_obj.get("reply_to_message").getAsJsonObject();
            String reply_id = reply_obj.get("message_id").getAsString();
            String message_list_raw = public_func.read_file(context, "message.json");
            JsonObject message_list = JsonParser.parseString(message_list_raw).getAsJsonObject();
            if (message_list.has(reply_id)) {
                JsonObject message_item_obj = message_list.get(reply_id).getAsJsonObject();
                String phone_number = message_item_obj.get("phone").getAsString();
                public_func.send_sms(context, phone_number, request_msg);
                return;
            }
            if (message_type_is_group) {
                Log.i(log_tag, "receive_handle: The message id could not be found, ignored.");
                return;
            }
        }
        if (message_obj.has("entities")) {
            String temp_command;
            JsonArray entities_arr = message_obj.get("entities").getAsJsonArray();
            JsonObject entities_obj_command = entities_arr.get(0).getAsJsonObject();
            if (entities_obj_command.get("type").getAsString().equals("bot_command")) {
                int command_offset = entities_obj_command.get("offset").getAsInt();
                int command_end_offset = command_offset + entities_obj_command.get("length").getAsInt();
                temp_command = request_msg.substring(command_offset, command_end_offset).trim().toLowerCase();
                command = temp_command;
                if (temp_command.contains("@")) {
                    int command_at_location = temp_command.indexOf("@");
                    command = temp_command.substring(0, command_at_location);
                    command_bot_username = temp_command.substring(command_at_location + 1);
                }
            }
        }
        if (message_type_is_group && !command_bot_username.equals(bot_username)) {
            Log.i(log_tag, "receive_handle: This is a Group conversation, but no conversation object was found.");
            return;

        }
        boolean has_command = false;
        switch (command) {
            case "/help":
            case "/start":
                request_body.text = getString(R.string.system_message_head) + "\n" + getString(R.string.available_command) + "\n" + getString(R.string.sendsms);
                if (message_type_is_group && !bot_username.equals("")) {
                    request_body.text = request_body.text.replace(" -", "@" + bot_username + " -");
                }
                has_command = true;
                break;
            case "/ping":
            case "/getinfo":
                request_body.text = getString(R.string.system_message_head) + "\n" + context.getString(R.string.current_battery_level) + get_battery_info(context) + "\n" + getString(R.string.current_network_connection_status) + public_func.get_network_type(context) + "\nSIM:" + public_func.get_sim_name(context);
                has_command = true;
                break;
            case "/log":
                request_body.text = getString(R.string.system_message_head) + public_func.read_log(context, 10);
                has_command = true;
                break;
            case "/sendsms":
                String[] msg_send_list = request_msg.split("\n");
                if (msg_send_list.length > 2) {
                    String msg_send_to = public_func.get_send_phone_number(msg_send_list[1]);
                    if (public_func.is_phone_number(msg_send_to)) {
                        StringBuilder msg_send_content = new StringBuilder();
                        for (int i = 2; i < msg_send_list.length; i++) {
                            if (msg_send_list.length != 3 && i != 2) {
                                msg_send_content.append("\n");
                            }
                            msg_send_content.append(msg_send_list[i]);
                        }
                        public_func.send_sms(context, msg_send_to, msg_send_content.toString());
                        return;
                    }
                    has_command = true;
                } else {
                    if (!message_type_is_group) {
                        send_sms_status = 0;
                        has_command = false;
                    }
                }
                request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.failed_to_get_information);
                break;
            default:
                if (!message_obj.get("chat").getAsJsonObject().get("type").getAsString().equals("private")) {
                    Log.i(log_tag, "receive_handle: The conversation is not Private and does not prompt an error.");
                    return;
                }
                request_body.text = context.getString(R.string.system_message_head) + "\n" + getString(R.string.unknown_command);
                break;
        }
        if (!has_command) {
            switch (send_sms_status) {
                case 0:
                    send_sms_status = 1;
                    request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.enter_number);
                    Log.i(log_tag, "receive_handle: Enter the interactive SMS sending mode.");
                    break;
                case 1:
                    String temp_to = public_func.get_send_phone_number(request_msg);
                    Log.d(log_tag, "receive_handle: " + temp_to);
                    if (public_func.is_phone_number(temp_to)) {
                        send_to_temp = temp_to;
                        request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.enter_content);
                        send_sms_status = 2;
                    } else {
                        send_sms_status = -1;
                        send_to_temp = null;
                        request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.unable_get_phone_number);

                    }
                    break;
                case 2:
                    public_func.send_sms(context, send_to_temp, request_msg);
                    return;
            }
        } else {
            send_sms_status = -1;
            send_to_temp = null;
        }

        String request_uri = public_func.get_url(bot_token, "sendMessage");
        RequestBody body = RequestBody.create(public_func.JSON, new Gson().toJson(request_body));
        Request send_request = new Request.Builder().url(request_uri).method("POST", body).build();
        Call call = okhttp_client.newCall(send_request);
        final String error_head = "Send reply failed:";
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
                String error_message = error_head + e.getMessage();
                public_func.write_log(context, error_message);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.code() != 200) {
                    assert response.body() != null;
                    String error_message = error_head + response.code() + " " + response.body().string();
                    public_func.write_log(context, error_message);
                }
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void get_me() {
        OkHttpClient okhttp_client_new = okhttp_client;
        String request_uri = public_func.get_url(bot_token, "getMe");
        Request request = new Request.Builder().url(request_uri).build();
        Call call = okhttp_client_new.newCall(request);
        Response response;
        try {
            response = call.execute();
        } catch (IOException e) {
            e.printStackTrace();
            public_func.write_log(context, "Get username failed:" + e.getMessage());
            return;
        }
        if (response.code() == 200) {
            String result = null;
            try {
                result = Objects.requireNonNull(response.body()).string();
            } catch (IOException e) {
                e.printStackTrace();
            }
            assert result != null;
            JsonObject result_obj = JsonParser.parseString(result).getAsJsonObject();
            if (result_obj.get("ok").getAsBoolean()) {
                bot_username = result_obj.get("result").getAsJsonObject().get("username").getAsString();
                have_bot_username = true;
                Log.d(log_tag, "bot_username: " + bot_username);
            }
        }

    }

    class thread_handle implements Runnable {
        @Override
        public void run() {
            Log.d(log_tag, "run: thread handle start");
            int chat_int_id = 0;
            try {
                chat_int_id = Integer.parseInt(chat_id);
            } catch (NumberFormatException e) {
                e.printStackTrace();
                //Avoid errors caused by unconvertible inputs.
            }
            if (chat_int_id < 0 && !have_bot_username) {
                new Thread(chat_command_service.this::get_me).start();
            }
            while (true) {
                int read_timeout = 5 * magnification;
                OkHttpClient okhttp_client_new = okhttp_client.newBuilder()
                        .readTimeout((read_timeout + 5), TimeUnit.SECONDS)
                        .writeTimeout((read_timeout + 5), TimeUnit.SECONDS)
                        .build();
                String request_uri = public_func.get_url(bot_token, "getUpdates");
                polling_json request_body = new polling_json();
                request_body.offset = offset;
                request_body.timeout = read_timeout;
                RequestBody body = RequestBody.create(public_func.JSON, new Gson().toJson(request_body));
                Request request = new Request.Builder().url(request_uri).method("POST", body).build();
                Call call = okhttp_client_new.newCall(request);
                Response response;
                try {
                    response = call.execute();
                    error_magnification = 1;
                } catch (IOException e) {
                    e.printStackTrace();
                    if (!public_func.check_network_status(context)) {
                        public_func.write_log(context, "No network connections available. ");
                        break;
                    }
                    int sleep_time = 5 * error_magnification;
                    public_func.write_log(context, "No network service,try again after " + sleep_time + " seconds.");
                    magnification = 1;
                    if (error_magnification <= 59) {
                        error_magnification++;
                    }
                    try {
                        Thread.sleep(sleep_time * 1000);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                    continue;

                }
                if (response.code() == 200) {
                    assert response.body() != null;
                    String result;
                    try {
                        result = Objects.requireNonNull(response.body()).string();
                    } catch (IOException e) {
                        e.printStackTrace();
                        continue;
                    }
                    JsonObject result_obj = JsonParser.parseString(result).getAsJsonObject();
                    if (result_obj.get("ok").getAsBoolean()) {
                        JsonArray result_array = result_obj.get("result").getAsJsonArray();
                        for (JsonElement item : result_array) {
                            receive_handle(item.getAsJsonObject());
                        }
                    }
                    if (magnification <= 11) {
                        magnification++;
                    }
                }
            }
        }
    }
    private String get_battery_info(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);
        assert batteryStatus != null;
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int battery_level = -1;
        if (level != -1 && scale != -1) {
            battery_level = (int) ((level / (float) scale) * 100);
        }
        if (battery_level > 100) {
            battery_level = 100;
        }

        String battery_level_string = battery_level + "%";
        int charge_status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        switch (charge_status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
            case BatteryManager.BATTERY_STATUS_FULL:
                battery_level_string += " (" + context.getString(R.string.charging) + ")";
                break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                int plug_status = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                switch (plug_status) {
                    case BatteryManager.BATTERY_PLUGGED_AC:
                    case BatteryManager.BATTERY_PLUGGED_USB:
                    case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                        battery_level_string += " (" + getString(R.string.not_charging) + ")";
                        break;
                }
                break;
        }

        return battery_level_string;

    }
    class stop_broadcast_receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(log_tag, "Received stop signal, quitting now...");
            stopSelf();
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    class network_changed_receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (public_func.check_network_status(context)) {
                if (!thread_main.isAlive()) {
                    public_func.write_log(context, "Network connections has been restored.");
                    thread_main = new Thread(new thread_handle());
                    thread_main.start();
                }
            }
        }
    }
}

