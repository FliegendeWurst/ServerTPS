package fliegendewurst.servertps;

import com.google.common.collect.EvictingQueue;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.commons.lang3.ThreadUtils;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = ServerTPS.MODID)
public final class DebugHandler {
	private static long lastFrame = 0;
	private static final ConcurrentHashMap<Long, Integer> THREAD_PRIORITIES = new ConcurrentHashMap<>();
	private static final HashMap<Long, Long> THREAD_TIMES = new HashMap<>();
	private static final HashMap<Long, String> THREAD_NAMES = new HashMap<>();
	private static final HashMap<Long, EvictingQueue<Float>> THREAD_CPU = new HashMap<>();
	private static final Lock LOCK = new ReentrantLock();

	@SubscribeEvent
	public static void onDrawDebugText(RenderGameOverlayEvent.Text event) {
		if (Minecraft.getInstance().gameSettings.showDebugInfo) {
			List<String> side = event.getRight();
			side.add("");
			side.add(String.format("Client tick: %.1f ms", ServerTPS.calculateClientTick()));
			side.add(String.format("Server TPS: %.1f", ServerTPS.calculateServerTPS()));
			side.add("");
			Map<Long, Float> threadCPU = new HashMap<>();
			LOCK.lock();
			THREAD_PRIORITIES.keySet().stream().filter(THREAD_CPU::containsKey).forEach(id ->
				threadCPU.put(id, THREAD_CPU.get(id).stream().reduce(0f, Float::sum) / THREAD_CPU.get(id).size())
			);
			threadCPU.keySet().stream()
					.sorted(Comparator.comparing(threadCPU::get).reversed())
					.forEach(id -> {
						float usage = threadCPU.get(id);
						if (usage < 1.0f) {
							return;
						}
						int priority = THREAD_PRIORITIES.get(id);
						if (priority != Thread.NORM_PRIORITY) {
							side.add(String.format("%s [prio %s]: %.1f%%",
									THREAD_NAMES.get(id),
									THREAD_PRIORITIES.get(id),
									threadCPU.get(id)));
						} else {
							side.add(String.format("%s: %.1f%%", THREAD_NAMES.get(id), threadCPU.get(id)));
						}
					});
			LOCK.unlock();
		}
	}

	private static ThreadMXBean tmxb;

	public static void measureCPU() {
		tmxb = ManagementFactory.getThreadMXBean();
		while (true) {
			long start = System.nanoTime();
			measureThreads();
			long timeTaken = System.nanoTime() - start;
			// only measure every 50 ms
			long timeToWait = 5000000 - timeTaken;
			if (timeToWait > 0) {
				try {
					Thread.sleep(timeToWait / 100000, (int) (timeToWait % 100000));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	private static void measureThreads() {
		//ServerTPS.LOGGER.debug("CPU time measurement supported: {}", tmxb.isThreadCpuTimeSupported());
		//ServerTPS.LOGGER.debug("CPU time measurement enabled: {}", tmxb.isThreadCpuTimeEnabled());
		if (!tmxb.isThreadCpuTimeEnabled()) {
			return;
		}

		// store thread priorities
		Collection<Thread> allThreads = ThreadUtils.getAllThreads();
		allThreads.forEach(thread -> THREAD_PRIORITIES.put(thread.getId(), thread.getPriority()));
		try {
			LOCK.lock();
			THREAD_PRIORITIES.keySet().removeIf(id -> allThreads.stream().noneMatch(thread -> thread.getId() == id));

			//long start = System.nanoTime();

			// compute usage since last frame
			long diff = System.nanoTime() - lastFrame;
			lastFrame = System.nanoTime();
			long[] ids = tmxb.getAllThreadIds();
			for (long id : ids) {
				float usage = (tmxb.getThreadCpuTime(id) - THREAD_TIMES.getOrDefault(id, 0L)) / (float) diff * 100.0f;
				THREAD_CPU.computeIfAbsent(id, x -> EvictingQueue.create(20)).add(usage);
				THREAD_TIMES.put(id, tmxb.getThreadCpuTime(id));
				ThreadInfo threadInfo = tmxb.getThreadInfo(id);
				if (threadInfo != null) {
					THREAD_NAMES.put(id, threadInfo.getThreadName());
				}
			}

			//long timeTaken = System.nanoTime() - start;
			//ServerTPS.LOGGER.debug("time measurement took {} ms", timeTaken / 100000);
		} finally {
			LOCK.unlock();
		}
	}
}