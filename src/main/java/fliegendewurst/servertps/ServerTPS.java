package fliegendewurst.servertps;

import com.google.common.collect.EvictingQueue;
import net.minecraft.network.play.server.SUpdateTimePacket;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("servertps")
public class ServerTPS {
	public static final String MODID = "servertps";
	public static final Logger LOGGER = LogManager.getLogger("ServerTPS");

	public ServerTPS() {
		if (FMLEnvironment.dist.isDedicatedServer()) {
			throw new IllegalStateException("client-side mod!");
		}

		MinecraftForge.EVENT_BUS.register(this);

		Thread measurementThread = new Thread(DebugHandler::measureCPU, "CPU Usage");
		measurementThread.setPriority(Thread.MIN_PRIORITY);
		measurementThread.start();
	}

	@SubscribeEvent
	public void onClientConnectedToServer(ClientPlayerNetworkEvent.LoggedInEvent event) {
		LOGGER.debug("client connected, starting TPS recording...");
		clientTicks.clear();
		serverTPS.clear();
		systemTime1 = 0;
		systemTime2 = 0;
		serverTime = 0;
	}

	public static EvictingQueue<Float> clientTicks = EvictingQueue.create(20);
	public static EvictingQueue<Float> serverTPS = EvictingQueue.create(3);
	private static long systemTime1 = 0;
	private static long systemTime2 = 0;
	private static long serverTime = 0;

	public static void onServerTick(SUpdateTimePacket packet) {
		if (systemTime1 == 0) {
			systemTime1 = System.currentTimeMillis();
			serverTime = packet.getTotalWorldTime();
		} else {
			long newSystemTime = System.currentTimeMillis();
			long newServerTime = packet.getTotalWorldTime();
			serverTPS.add((((float) (newServerTime - serverTime)) / (((float) (newSystemTime - systemTime1)) / 50.0f)) * 20.0f);
			systemTime1 = newSystemTime;
			serverTime = newServerTime;
		}
	}

	// TODO: two event listeners with high and low priority
	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase == TickEvent.Phase.START) {
			systemTime2 = System.currentTimeMillis();
		} else {
			long newSystemTime = System.currentTimeMillis();
			float newClientTick = ((float) newSystemTime) - systemTime2;
			//LOGGER.debug("adding {} to client TPS [{}, {}, {}, {}]", newClientTick, systemTime2, newSystemTime, clientTime, newClientTime);
			//LOGGER.debug("client tick took: {} ms", newSystemTime - systemTime2);
			clientTicks.add(newClientTick);
		}
	}

	public static float calculateClientTick() {
		float sum = 0.0f;
		for (Float f : clientTicks) {
			sum += f;
		}
		return sum / (float) clientTicks.size();
	}

	public static float calculateServerTPS() {
		float sum = 0.0f;
		for (Float f : serverTPS) {
			sum += f;
		}
		return sum / (float) serverTPS.size();
	}
}
