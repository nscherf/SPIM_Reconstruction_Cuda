package fastspim;

import fastspim.NativeSPIMReconstructionCuda.DataProvider;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.awt.Scrollbar;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;
import java.util.Vector;

public class Deconvolve_Cuda_Interactive implements PlugIn, DataProvider {

	@Override
	public void run(String arg) {
		final GenericDialogPlus gd = new GenericDialogPlus("Deconvolve Cuda (Interactive)");
		gd.addDirectoryField("SPIM_Folder", "");
		gd.addDirectoryField("PSF_Folder", "");
		String[] choice = new String[] { "INDEPENDENT", "EFFICIENT_BAYESIAN", "OPTIMIZATION_1", "OPTIMIZATION_2" };
		gd.addChoice("Iteration_type", choice, "OPTIMIZATION_1");
		gd.showDialog();
		if(gd.wasCanceled())
			return;

		String spimfolderString = gd.getNextString();
		String psffolderString = gd.getNextString();
		int psfType = gd.getNextChoiceIndex();

		startPreview(spimfolderString, psffolderString, psfType);
	}

	private Worker worker = null;
	private ImagePlus previewImage = null;

	@Override
	public short[][] getNextPlane() {
		return worker.getNextPlane();
	}

	@Override
	public void returnNextPlane(short[] plane) {
		previewImage.getStack().setPixels(plane, 1);
		previewImage.updateAndDraw();
	}

	void startPreview(String spimfolderString, String psffolderString, int psfType) {
		File spimfolder = new File(spimfolderString);
		File psffolder = new File(psffolderString);
		File outputfolder = new File(spimfolder, "output");
		File weightsfolder = new File(outputfolder, "masks");
		String[] fileNames = weightsfolder.list(new FilenameFilter() {
			@Override
			public boolean accept(File arg0, String arg1) {
				return arg1.endsWith(".raw");
			}
		});

		int nViews = fileNames.length;


		File[] datafiles = new File[nViews];
		String[] weightfiles = new String[nViews];
		String[] kernelfiles = new String[nViews];
		for(int i = 0; i < nViews; i++) {
			datafiles[i] = new File(outputfolder, fileNames[i]);
			weightfiles[i] = new File(weightsfolder, fileNames[i]).getAbsolutePath();
			kernelfiles[i] = new File(psffolder, fileNames[i]).getAbsolutePath();
		}
		int[] dims = null;
		int[] psfDims = null;

		try {
			dims = readDimensions(new File(outputfolder, "dims.txt"));
			psfDims = readDimensions(new File(psffolder, "dims.txt"));
		} catch(Exception e) {
			IJ.handleException(e);
			return;
		}
		int w = dims[0];
		int h = dims[1];
		int d = dims[2];
		int kernelW = psfDims[0];
		int kernelH = psfDims[1];

		int plane = d / 2;
		int iterations = 5;

		short[][] datacache = new short[nViews][w * h];

		ImageStack previewImageStack = new ImageStack(w, h);
		previewImageStack.addSlice("deconvolved", new ShortProcessor(w, h));
		for(int v = 0; v < nViews; v++)
			previewImageStack.addSlice("View " + v, new ShortProcessor(w, h, datacache[v], null));

		previewImage = new ImagePlus("Preview", previewImageStack);
		previewImage.show();


		worker = new Worker(datafiles, datacache);

		NativeSPIMReconstructionCuda.setDataProvider(this);
		NativeSPIMReconstructionCuda.deconvolve_init(w, h, 1, weightfiles, kernelfiles, kernelH, kernelW, psfType, nViews);

		GenericDialog gd = new GenericDialog("Preview");
		gd.addSlider("Plane", 0, d - 1, plane);
		gd.addSlider("#Iterations", 0, 50, iterations);

		@SuppressWarnings("rawtypes")
		Vector sliders = gd.getSliders();
		final Scrollbar planeSlider = (Scrollbar)sliders.get(0);
		final Scrollbar iterationSlider = (Scrollbar)sliders.get(1);
		planeSlider.addAdjustmentListener(new AdjustmentListener() {
			@Override
			public void adjustmentValueChanged(AdjustmentEvent arg0) {
				worker.push(iterationSlider.getValue(), planeSlider.getValue());
			}
		});
		iterationSlider.addAdjustmentListener(new AdjustmentListener() {
			@Override
			public void adjustmentValueChanged(AdjustmentEvent arg0) {
				worker.push(iterationSlider.getValue(), planeSlider.getValue());
			}
		});
		gd.setModal(false);
		gd.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				stopPreview();
			}
		});
		worker.executeOnce(iterations, plane);

		gd.showDialog();

		ImageProcessor ip = previewImage.getProcessor();
		double min = ip.getStatistics().min;
		double max = ip.getStatistics().max;
		ip.setMinAndMax(min, max);
		previewImage.updateAndDraw();
	}

	static void loadDataCache(File[] dataFiles, short[][] datacache, int plane) {
		for(int v = 0; v < dataFiles.length; v++) {
			try {
				readFullyMapped(dataFiles[v], plane, datacache[v]);
			} catch (IOException e) {
				IJ.handleException(e);
			}
		}
		System.out.println("loaded data for plane " + plane);
	}

	void stopPreview() {
		NativeSPIMReconstructionCuda.clearDataProvider();
		if(worker != null) {
			worker.shutdown();
			worker = null;
		}
		if(previewImage != null) {
			previewImage.close();
			previewImage = null;
		}
		try {
			NativeSPIMReconstructionCuda.deconvolve_quit();
		} catch(Exception e) {
			System.out.println(e.getMessage());
		}
	}

	private final int[] readDimensions(File file) throws IOException {
		BufferedReader in = new BufferedReader(new FileReader(file));
		ArrayList<String> lines = new ArrayList<String>(3);
		String line = null;
		while((line = in.readLine()) != null)
			lines.add(line);
		in.close();

		int[] ret = new int[lines.size()];
		for(int i = 0; i < ret.length; i++)
			ret[i] = Integer.parseInt(lines.get(i));

		return ret;
	}

	private static void readFullyMapped(File f, int plane, short[] ret) throws IOException {
		RandomAccessFile ra = new RandomAccessFile(f, "r");
		FileChannel fc = ra.getChannel();
		long position = (long)plane * ret.length * 2L;
		long size = ret.length * 2L;
		ShortBuffer buf = fc.map(MapMode.READ_ONLY, position, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
		buf.get(ret);
		fc.close();
		ra.close();
	}

	private static class Worker {
		private Thread thread;
		private final Object lock = new Object();
		private boolean shutdown = false;
		private int lastPlane = -1;
		private boolean firstPlane = true;

		private File[] datafiles = null;
		private short[][] datacache;

		private static class Event {
			private int iterations;
			private int plane;

			Event(int iterations, int plane) {
				this.iterations = iterations;
				this.plane = plane;
			}
		}

		public short[][] getNextPlane() {
			if(firstPlane) {
				firstPlane = false;
				return datacache;
			}
			return null;
		}

		private Event event = null;

		public Worker(File[] dataFiles, short[][] datacache) {
			this.datafiles = dataFiles;
			this.datacache = datacache;
			thread = new Thread() {
				@Override
				public void run() {
					loop();
				}
			};
			thread.start();
		}

		public void executeOnce(int iterations, int plane) {
			if(plane != lastPlane)
				loadDataCache(datafiles, datacache, plane);
			firstPlane = true;
			System.out.println("Deconvolving plane " + plane);
			NativeSPIMReconstructionCuda.deconvolve_interactive(iterations);
			lastPlane = plane;
		}

		public void loop() {
			while(!shutdown) {
				Event e = poll();
				// happens if shutdown
				if(e == null)
					return;
				if(e.plane != lastPlane) {
					loadDataCache(datafiles, datacache, e.plane);
				}
				firstPlane = true;
				System.out.println("Deconvolving plane " + e.plane);
				NativeSPIMReconstructionCuda.deconvolve_interactive(e.iterations);
				lastPlane = e.plane;
			}
		}

		public void push(int iterations, int plane) {
			synchronized(lock) {
				event = new Event(iterations, plane);
			}
			synchronized(this) {
				notifyAll();
			}
		}

		public Event poll() {
			if(event == null) {
				synchronized(this) {
					try {
						wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
			Event ret = null;
			synchronized(lock) {
				ret = event;
				event = null;
			}
			return ret;
		}

		public void shutdown() {
			shutdown = true;
			synchronized(this) {
				notifyAll();
			}
			try {
				thread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}