package zumito.zumactions.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import zumito.zumactions.network.PendingRequestsPayload;

// Caché del lado del cliente de las solicitudes entrantes del jugador local, para poder
// dibujar el tag flotante sobre la cabeza de cada emisor sin tener que preguntarle al
// servidor en cada frame. Se reemplaza entero cada vez que llega un snapshot nuevo.
public final class ClientPendingRequests {
	private static Map<UUID, String> labelBySender = Map.of();

	private ClientPendingRequests() {
	}

	// Devuelve las entradas que no estaban en el snapshot anterior, para poder avisarle
	// al jugador ("mirá a X y presioná <tecla>") solo cuando la solicitud es nueva.
	public static List<PendingRequestsPayload.Entry> update(List<PendingRequestsPayload.Entry> entries) {
		Map<UUID, String> next = new HashMap<>();
		List<PendingRequestsPayload.Entry> added = new ArrayList<>();
		for (PendingRequestsPayload.Entry entry : entries) {
			next.put(entry.senderId(), entry.label());
			if (!labelBySender.containsKey(entry.senderId())) {
				added.add(entry);
			}
		}
		labelBySender = next;
		return added;
	}

	public static void clear() {
		labelBySender = Map.of();
	}

	public static String labelFor(UUID senderId) {
		return labelBySender.get(senderId);
	}
}
