package dev.radioblock;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;

import java.util.Map;
import java.util.UUID;

/** Подключение к Simple Voice Chat: пересылает голос с рации-передатчика на рации-приёмники той же волны. */
public final class RadioVoicePlugin implements VoicechatPlugin {

    private final RadioManager manager;

    public RadioVoicePlugin(RadioManager manager) {
        this.manager = manager;
    }

    @Override
    public String getPluginId() {
        return "radioblock";
    }

    @Override
    public void initialize(VoicechatApi api) {
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    // Вызывается из потока голосового сервера - работаем только с потокобезопасными снимками.
    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection senderConn = event.getSenderConnection();
        if (senderConn == null) return;

        UUID sender = senderConn.getPlayer().getUuid();
        RadioManager.PlayerPos senderPos = manager.positions.get(sender);
        if (senderPos == null) return;

        RadioManager.Radio tx = manager.nearestTransmitter(senderPos);
        if (tx == null) return;

        VoicechatServerApi api = event.getVoicechat();
        int frequency = tx.frequency;
        float range = (float) manager.listenRange;

        for (Map.Entry<UUID, RadioManager.PlayerPos> entry : manager.positions.entrySet()) {
            UUID id = entry.getKey();
            if (id.equals(sender)) continue;

            RadioManager.Radio rx = manager.nearestListener(entry.getValue(), frequency);
            if (rx == null) continue;

            VoicechatConnection conn = api.getConnectionOf(id);
            if (conn == null) continue;

            LocationalSoundPacket packet = event.getPacket().locationalSoundPacketBuilder()
                    .position(api.createPosition(rx.key.x() + 0.5, rx.key.y() + 0.5, rx.key.z() + 0.5))
                    .distance(range)
                    .build();
            api.sendLocationalSoundPacketTo(conn, packet);
        }
    }
}
