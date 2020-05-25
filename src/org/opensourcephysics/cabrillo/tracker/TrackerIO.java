/*
* The tracker package defines a set of video/image analysis tools
* built on the Open Source Physics framework by Wolfgang Christian.
*
* Copyright (c) 2019  Douglas Brown
*
* Tracker is free software; you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation; either version 3 of the License, or
* (at your option) any later version.
*
* Tracker is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Tracker; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place, Suite 330, Boston MA 02111-1307 USA
* or view the license online at <http://www.gnu.org/copyleft/gpl.html>
*
* For additional Tracker information and documentation, please see
* <http://physlets.org/tracker/>.
*/
package org.opensourcephysics.cabrillo.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.zip.ZipEntry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileFilter;

import org.opensourcephysics.controls.ControlsRes;
import org.opensourcephysics.controls.ListChooser;
import org.opensourcephysics.controls.OSPLog;
import org.opensourcephysics.controls.XML;
import org.opensourcephysics.controls.XMLControl;
import org.opensourcephysics.controls.XMLControlElement;
import org.opensourcephysics.controls.XMLProperty;
import org.opensourcephysics.desktop.OSPDesktop;
import org.opensourcephysics.display.DataTable;
import org.opensourcephysics.display.OSPRuntime;
import org.opensourcephysics.display.Renderable;
import org.opensourcephysics.media.core.ImageCoordSystem;
import org.opensourcephysics.media.core.ImageVideo;
import org.opensourcephysics.media.core.MediaRes;
import org.opensourcephysics.media.core.Video;
import org.opensourcephysics.media.core.VideoClip;
import org.opensourcephysics.media.core.VideoIO;
import org.opensourcephysics.media.core.VideoType;
import org.opensourcephysics.tools.FontSizer;
import org.opensourcephysics.tools.LibraryResource;
import org.opensourcephysics.tools.LibraryTreePanel;
import org.opensourcephysics.tools.Resource;
import org.opensourcephysics.tools.ResourceLoader;

import javajs.async.AsyncFileChooser;
import javajs.async.AsyncSwingWorker;
import javajs.async.SwingJSUtils.Performance;

/**
 * This provides static methods for managing video and text input/output.
 *
 * @author Douglas Brown
 */
@SuppressWarnings("serial")
public class TrackerIO extends VideoIO {

	public interface TrackerMonitor {

		void stop();

		void setFrameCount(int count);

		void close();

		int getFrameCount();

		void setProgressAsync(int progress);

		void restart();

		String getName();

		void setTitle(String title);

	}

	protected static final String TAB = "\t", SPACE = " ", //$NON-NLS-1$ //$NON-NLS-2$
			COMMA = ",", SEMICOLON = ";"; //$NON-NLS-1$ //$NON-NLS-2$
	protected static final Runnable NULL_RUNNABLE = new Runnable() {
		@Override
		public void run() {
		}
	};
	protected static SingleExtFileFilter zipFileFilter, trkFileFilter, trzFileFilter;
	protected static SingleExtFileFilter videoAndTrkFileFilter, txtFileFilter, jarFileFilter;
	protected static String defaultDelimiter = TAB; // tab delimiter by default
	protected static String delimiter = defaultDelimiter;
	protected static Map<String, String> delimiters = new TreeMap<String, String>();
	protected static Map<String, String> customDelimiters = new TreeMap<String, String>();
	protected static boolean isffmpegError = false;
	protected static TFrame theFrame;
	protected static PropertyChangeListener ffmpegListener;
	protected static boolean loadInSeparateThread = true;
	private static Set<TrackerMonitor> monitors = new HashSet<>();
	protected static double defaultBadFrameTolerance = 0.2;
	protected static boolean dataCopiedToClipboard;

	static {
		if (!OSPRuntime.isJS) /** @j2sNative */
		{
			ffmpegListener = new PropertyChangeListener() {
				@Override
				public void propertyChange(PropertyChangeEvent e) {
					if (e.getPropertyName().equals("ffmpeg_error")) { //$NON-NLS-1$
						if (!isffmpegError) { // first error thrown
							isffmpegError = true;
							if (!Tracker.warnXuggleError) {
								if (e.getNewValue() != null) {
									String s = e.getNewValue().toString();
									int n = s.indexOf("]"); //$NON-NLS-1$
									if (n > -1)
										s = s.substring(n + 1);
									s += TrackerRes.getString("TrackerIO.ErrorFFMPEG.LogMessage"); //$NON-NLS-1$
									OSPLog.warning(s);
								}
								return;
							}
							// warn user that a Xuggle error has occurred
							Box box = Box.createVerticalBox();
							box.add(new JLabel(TrackerRes.getString("TrackerIO.Dialog.ErrorFFMPEG.Message1"))); //$NON-NLS-1$
							String error = e.getNewValue().toString();
							int n = error.lastIndexOf("]"); //$NON-NLS-1$
							if (n > -1) {
								error = error.substring(n + 1).trim();
							}
							box.add(new JLabel("  ")); //$NON-NLS-1$
							JLabel erLabel = new JLabel("\"" + error + "\""); //$NON-NLS-1$ //$NON-NLS-2$
							erLabel.setBorder(BorderFactory.createEmptyBorder(0, 60, 0, 0));
							box.add(erLabel);
							box.add(new JLabel("  ")); //$NON-NLS-1$
							box.add(new JLabel(TrackerRes.getString("TrackerIO.Dialog.ErrorFFMPEG.Message2"))); //$NON-NLS-1$

							box.add(new JLabel("  ")); //$NON-NLS-1$
							box.setBorder(BorderFactory.createEmptyBorder(20, 15, 0, 15));

							final JDialog dialog = new JDialog(theFrame, false);
							JPanel contentPane = new JPanel(new BorderLayout());
							dialog.setContentPane(contentPane);
							contentPane.add(box, BorderLayout.CENTER);
							JButton closeButton = new JButton(TrackerRes.getString("Dialog.Button.Close")); //$NON-NLS-1$
							closeButton.setForeground(new Color(0, 0, 102));
							closeButton.addActionListener(new ActionListener() {
								@Override
								public void actionPerformed(ActionEvent e) {
									dialog.setVisible(false);
								}
							});
							JButton dontShowAgainButton = new JButton(
									TrackerRes.getString("Tracker.Dialog.NoVideoEngine.Checkbox")); //$NON-NLS-1$
							dontShowAgainButton.setForeground(new Color(0, 0, 102));
							dontShowAgainButton.addActionListener(new ActionListener() {
								@Override
								public void actionPerformed(ActionEvent e) {
									Tracker.warnXuggleError = false;
									dialog.setVisible(false);
								}
							});
							JPanel buttonbar = new JPanel();
							buttonbar.add(dontShowAgainButton);
							buttonbar.add(closeButton);
							buttonbar.setBorder(BorderFactory.createEtchedBorder());
							contentPane.add(buttonbar, BorderLayout.SOUTH);
							FontSizer.setFonts(dialog, FontSizer.getLevel());
							dialog.pack();
							dialog.setTitle(TrackerRes.getString("TrackerIO.Dialog.ErrorFFMPEG.Title")); //$NON-NLS-1$
							// center dialog on the screen
							Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
							int x = (dim.width - dialog.getBounds().width) / 2;
							int y = (dim.height - dialog.getBounds().height) / 2;
							dialog.setLocation(x, y);
							dialog.setVisible(true);
						}
					}
				}
			};
			OSPLog.getOSPLog().addPropertyChangeListener(ffmpegListener);
		}
		zipFileFilter = new SingleExtFileFilter("zip", TrackerRes.getString("TrackerIO.ZipFileFilter.Description")); //$NON-NLS-1$ //$NON-NLS-2$
		trzFileFilter = new SingleExtFileFilter("trz", TrackerRes.getString("TrackerIO.ZIPResourceFilter.Description")); //$NON-NLS-1$ //$NON-NLS-2$
		txtFileFilter = new SingleExtFileFilter("txt", TrackerRes.getString("TrackerIO.TextFileFilter.Description")); //$NON-NLS-1$ //$NON-NLS-2$
		jarFileFilter = new SingleExtFileFilter("jar", TrackerRes.getString("TrackerIO.JarFileFilter.Description")); //$NON-NLS-1$ //$NON-NLS-2$
		trkFileFilter = new SingleExtFileFilter("trk", TrackerRes.getString("TrackerIO.DataFileFilter.Description")) { //$NON-NLS-1$ //$NON-NLS-2$

			@Override
			public boolean accept(File f, boolean checkDir) {
				return (checkDir && f.isDirectory() || zipFileFilter.accept(f, false) || trzFileFilter.accept(f, false)
						|| super.accept(f, false));
			}

		};

		videoAndTrkFileFilter = new SingleExtFileFilter(null,
				TrackerRes.getString("TrackerIO.VideoAndDataFileFilter.Description")) { //$NON-NLS-1$ 
			@Override
			public boolean accept(File f, boolean checkDir) {
				return (checkDir && f.isDirectory() || trkFileFilter.accept(f, false)
						|| videoFileFilter.accept(f, false) || super.accept(f, false));
			}
		};

		delimiters.put(TrackerRes.getString("TrackerIO.Delimiter.Tab"), TAB); //$NON-NLS-1$
		delimiters.put(TrackerRes.getString("TrackerIO.Delimiter.Space"), SPACE); //$NON-NLS-1$
		delimiters.put(TrackerRes.getString("TrackerIO.Delimiter.Comma"), COMMA); //$NON-NLS-1$
		delimiters.put(TrackerRes.getString("TrackerIO.Delimiter.Semicolon"), SEMICOLON); //$NON-NLS-1$
	}

	/**
	 * private constructor to prevent instantiation
	 */
	private TrackerIO() {
		/** empty block */
	}

	/**
	 * Writes TrackerPanel data to the specified file. If the file is null it brings
	 * up a chooser.
	 *
	 * @param file         the file to write to
	 * @param trackerPanel the TrackerPanel
	 * @return the file written to, or null if not written
	 */
	public static File save(File file, TrackerPanel trackerPanel) {
		trackerPanel.restoreViews();
		getChooser().setAcceptAllFileFilterUsed(false);
		chooser.addChoosableFileFilter(trkFileFilter);
		chooser.setAccessory(null);
		if (file == null && trackerPanel.getDataFile() == null) {
			VideoClip clip = trackerPanel.getPlayer().getVideoClip();
			if (clip.getVideoPath() != null) {
				File dir = new File(clip.getVideoPath()).getParentFile();
				chooser.setCurrentDirectory(dir);
			}
		}

		boolean isNew = file == null;
		file = save(file, trackerPanel, TrackerRes.getString("TrackerIO.Dialog.SaveTab.Title")); //$NON-NLS-1$
		chooser.removeChoosableFileFilter(trkFileFilter);
		chooser.setAcceptAllFileFilterUsed(true);
		if (isNew && file != null) {
			Tracker.addRecent(XML.getAbsolutePath(file), false); // add at beginning
			TMenuBar.refreshMenus(trackerPanel, TMenuBar.REFRESH_TRACKERIO_SAVE);
		}
		return file;
	}

	/**
	 * Saves a tabset in the specified file. If the file is null this brings up a
	 * chooser.
	 *
	 * @param file  the file to write to
	 * @param frame the TFrame
	 * @return the file written to, or null if not written
	 */
	public static File saveTabset(File file, TFrame frame) {
		// count tabs with data files or unchanged (newly opened) videos
		int n = 0;
		for (int i = 0; i < frame.getTabCount(); i++) {
			TrackerPanel trackerPanel = frame.getTrackerPanel(i);
			if (trackerPanel.getDataFile() != null) {
				n++;
				continue;
			}
			Video video = trackerPanel.getVideo();
			if (!trackerPanel.changed && video != null) {
				String path = (String) video.getProperty("absolutePath"); //$NON-NLS-1$
				if (path != null) {
					n++;
					continue;
				}
			}
			// notify user that tab must be saved in order be in tabset
			int selected = JOptionPane.showConfirmDialog(frame,
					TrackerRes.getString("TrackerIO.Dialog.TabMustBeSaved.Message1") //$NON-NLS-1$
							+ " " + i + " (\"" + frame.getTabTitle(i) + "\") " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							+ TrackerRes.getString("TrackerIO.Dialog.TabMustBeSaved.Message2") + XML.NEW_LINE //$NON-NLS-1$
							+ TrackerRes.getString("TrackerIO.Dialog.TabMustBeSaved.Message3"), //$NON-NLS-1$
					TrackerRes.getString("TrackerIO.Dialog.TabMustBeSaved.Title"), //$NON-NLS-1$
					JOptionPane.YES_NO_CANCEL_OPTION);
			if (selected == JOptionPane.CANCEL_OPTION) {
				return null;
			} else if (selected != JOptionPane.YES_OPTION) {
				continue;
			}
			getChooser().setAccessory(null);
			File newFile = save(null, trackerPanel, TrackerRes.getString("TrackerIO.Dialog.SaveTab.Title")); //$NON-NLS-1$
			if (newFile == null) {
				return null;
			}
			Tracker.addRecent(XML.getAbsolutePath(newFile), false); // add at beginning
			n++;
		}
		// abort if no data files
		if (n == 0) {
			JOptionPane.showMessageDialog(frame, TrackerRes.getString("TrackerIO.Dialog.NoTabs.Message"), //$NON-NLS-1$
					TrackerRes.getString("TrackerIO.Dialog.NoTabs.Title"), //$NON-NLS-1$
					JOptionPane.WARNING_MESSAGE);
			return null;
		}
		// if file is null, use chooser to get a file
		if (file == null) {
			File[] files = getChooserFiles("save tabset"); //$NON-NLS-1$
			if (files == null || !canWrite(files[0]))
				return null;
			file = files[0];
		}
		frame.tabsetFile = file;
		XMLControl xmlControl = new XMLControlElement(frame);
		xmlControl.write(XML.getAbsolutePath(file));
		Tracker.addRecent(XML.getAbsolutePath(file), false); // add at beginning
		TMenuBar.refreshMenus(frame.getTrackerPanel(frame.getSelectedTab()), TMenuBar.REFRESH_TRACKERIO_SAVETABSET);
		return file;
	}

	/**
	 * A Stop-gap method to allow Java-only functionality.
	 * 
	 * @param type
	 * @return
	 */
	@Deprecated
	public static File[] getChooserFiles(String type) {
		return getChooserFilesAsync(type, null);
	}

	/**
	 * Displays a file chooser and returns the chosen files.
	 *
	 * @param type may be open, open video, save, insert image, export file, import
	 *             file, save tabset, open data, open trk
	 * @return the files, or null if no files chosen
	 */
	public static File[] getChooserFilesAsync(String type, Function<File[], Void> processFiles) {

		// BH Java will run all this synchronously anyway.
		AsyncFileChooser chooser = getChooser();
		// open tracker or video file

		Runnable resetChooser = new Runnable() {

			@Override
			public void run() {
				chooser.resetChoosableFileFilters();
				chooser.setSelectedFile(null);
			}

		};

		Runnable okOpen = new Runnable() {

			@Override
			public void run() {
				if (processFiles != null) {
					File file = chooser.getSelectedFile();
					resetChooser.run();
					processFiles.apply(new File[] { file });
				}
			}

		};

		Runnable okSave = new Runnable() {

			@Override
			public void run() {
				File file = chooser.getSelectedFile();
				resetChooser.run();
				if (canWrite(file))
					processFiles.apply(new File[] { file });
			}

		};
		File ret = null;
		boolean isSave = false;
		if (type.toLowerCase().equals("open")) { //$NON-NLS-1$
			chooser.setMultiSelectionEnabled(false);
			chooser.setAcceptAllFileFilterUsed(true);
			chooser.addChoosableFileFilter(videoAndTrkFileFilter);
			chooser.setFileFilter(videoAndTrkFileFilter);
			chooser.setDialogTitle(TrackerRes.getString("TrackerIO.Dialog.Open.Title")); //$NON-NLS-1$
			chooser.showOpenDialog(null, okOpen, resetChooser);
		} else if (type.toLowerCase().equals("open trk")) { //$NON-NLS-1$
			// open tracker file
			chooser.setMultiSelectionEnabled(false);
			chooser.setAccessory(null);
			chooser.setAcceptAllFileFilterUsed(true);
			chooser.addChoosableFileFilter(trkFileFilter);
			chooser.setFileFilter(trkFileFilter);
			chooser.setDialogTitle(TrackerRes.getString("TrackerIO.Dialog.Open.Title")); //$NON-NLS-1$
			chooser.showOpenDialog(null, okOpen, resetChooser);
		} else if (type.toLowerCase().equals("open any")) { //$NON-NLS-1$
			// open any file
			chooser.setMultiSelectionEnabled(false);
			chooser.setDialogTitle(TrackerRes.getString("TrackerIO.Dialog.Open.Title")); //$NON-NLS-1$
			chooser.showOpenDialog(null, okOpen, resetChooser);
		} else if (type.toLowerCase().equals("open video")) { // open video //$NON-NLS-1$
			chooser.setMultiSelectionEnabled(false);
			chooser.setAcceptAllFileFilterUsed(true);
			chooser.addChoosableFileFilter(videoFileFilter);
			chooser.setFileFilter(videoFileFilter);
			chooser.setDialogTitle(TrackerRes.getString("TrackerIO.Dialog.Open.Title")); //$NON-NLS-1$
			chooser.showOpenDialog(null, okOpen, resetChooser);
		} else if (type.toLowerCase().equals("open data")) { // open text data file //$NON-NLS-1$
			chooser.setMultiSelectionEnabled(false);
			chooser.setAcceptAllFileFilterUsed(true);
			chooser.addChoosableFileFilter(txtFileFilter);
			chooser.setDialogTitle(TrackerRes.getString("TrackerIO.Dialog.OpenData.Title")); //$NON-NLS-1$
			chooser.showOpenDialog(null, okOpen, resetChooser);
		} else if (type.toLowerCase().equals("open ejs")) { // open ejs //$NON-NLS-1$
			chooser.setMultiSelectionEnabled(false);
			chooser.setAcceptAllFileFilterUsed(true);
			chooser.addChoosableFileFilter(jarFileFilter);
			chooser.setDialogTitle(TrackerRes.getString("TrackerIO.Dialog.OpenEJS.Title")); //$NON-NLS-1$
			chooser.showOpenDialog(null, okOpen, resetChooser);
		} else if (type.toLowerCase().equals("import file")) { //$NON-NLS-1$
			// import elements from a tracker file
			chooser.setAccessory(null);
			chooser.setMultiSelectionEnabled(false);
			chooser.setAcceptAllFileFilterUsed(true);
			chooser.addChoosableFileFilter(trkFileFilter);
			chooser.setFileFilter(trkFileFilter);
			chooser.setDialogTitle(TrackerRes.getString("TrackerIO.Dialog.Import.Title")); //$NON-NLS-1$
			chooser.showOpenDialog(null, okOpen, resetChooser);
		} else if (type.toLowerCase().equals("export file")) { //$NON-NLS-1$
			// export elements to a tracker file
			isSave = true;
			chooser.setAccessory(null);
			chooser.setMultiSelectionEnabled(false);
			chooser.setAcceptAllFileFilterUsed(true);
			chooser.addChoosableFileFilter(trkFileFilter);
			chooser.setFileFilter(trkFileFilter);
			chooser.setDialogTitle(TrackerRes.getString("TrackerIO.Dialog.Export.Title")); //$NON-NLS-1$
			chooser.showSaveDialog(null, okSave, resetChooser);
		} else if (type.toLowerCase().equals("save")) { // save a file //$NON-NLS-1$
			isSave = true;
			chooser.setAccessory(null);
			// note this sets no file filters nor title
			chooser.setMultiSelectionEnabled(false);
			chooser.showSaveDialog(null, okSave, resetChooser);
		} else if (type.toLowerCase().equals("save tabset")) { //$NON-NLS-1$
			isSave = true;
			// save a tabset file
			chooser.setAccessory(null);
			chooser.setAcceptAllFileFilterUsed(false);
			chooser.addChoosableFileFilter(trkFileFilter);
			chooser.setDialogTitle(TrackerRes.getString("TrackerIO.Dialog.SaveTabset.Title")); //$NON-NLS-1$
			String filename = ""; //$NON-NLS-1$
			File file = new File(filename + "." + defaultXMLExt); //$NON-NLS-1$
			String parent = XML.getDirectoryPath(filename);
			if (!parent.equals("")) { //$NON-NLS-1$
				XML.createFolders(parent);
				chooser.setCurrentDirectory(new File(parent));
			}
			chooser.setSelectedFile(file);
			chooser.showSaveDialog(null, new Runnable() {

				@Override
				public void run() {
					resetChooser.run();
					if (processFiles != null) {
						processFiles.apply(new File[] { fixXML(chooser) });
					}
				}

			}, resetChooser);
			ret = (processFiles != null || chooser.getSelectedOption() != JFileChooser.APPROVE_OPTION ? null
					: fixXML(chooser));
		} else {
			return getChooserFilesAsync(type, processFiles);
		}
		ret = processChoose(chooser, ret, processFiles != null);
		if (processFiles == null) {
			resetChooser.run();
		}
		return (ret == null || isSave && !canWrite(ret) ? null : new File[] { ret });
	}

	protected static File fixXML(AsyncFileChooser chooser) {
		File file = chooser.getSelectedFile();
		if (!defaultXMLExt.equals(getExtension(file))) {
			String filename = XML.stripExtension(file.getPath());
			File f = new File(filename + "." + defaultXMLExt); //$NON-NLS-1$
			if (OSPRuntime.isJS) {
				// BH transfer the bytes
				OSPRuntime.jsutil.setFileBytes(f, OSPRuntime.jsutil.getBytes(file));
				OSPRuntime.cacheJSFile(f, true);
			}
			file = f;
		}
		return file;
	}

	/**
	 * Displays a file chooser and returns the chosen file, adding or changing the
	 * extension to match the specified extension.
	 *
	 * @param extension the extension
	 * @return the file, or null if no file chosen
	 */
	public static File getChooserFileForExtension(String extension) {
		if (extension != null && !extension.trim().equals("")) { //$NON-NLS-1$
			extension = extension.trim().toLowerCase();
		} else {
			extension = null;
		}
		final String ext = extension;
		getChooser().setDialogTitle(MediaRes.getString("VideoIO.Dialog.SaveVideoAs.Title")); //$NON-NLS-1$
		chooser.resetChoosableFileFilters();
		chooser.setAccessory(null);
		chooser.setMultiSelectionEnabled(false);
		chooser.setAcceptAllFileFilterUsed(ext != null);
		if (ext != null) {
			FileFilter fileFilter = new FileFilter() {
				@Override
				public boolean accept(File f) {
					if (f == null)
						return false;
					if (f.isDirectory())
						return true;
					if (ext.equals(getExtension(f)))
						return true;
					return false;
				}

				@Override
				public String getDescription() {
					String file = TrackerRes.getString("TMenuBar.Menu.File").toLowerCase(); //$NON-NLS-1$
					return ext.toUpperCase() + " " + file + " (." + ext + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
			};
			chooser.addChoosableFileFilter(fileFilter);
			chooser.setFileFilter(fileFilter);
		}
		int result = chooser.showSaveDialog(null);
		File file = chooser.getSelectedFile();
		chooser.resetChoosableFileFilters();
		chooser.setSelectedFile(new File("")); //$NON-NLS-1$
		if (file == null)
			return null;
		if (result == JFileChooser.APPROVE_OPTION) {
			if (ext != null && !ext.equals(XML.getExtension(file.getName()))) {
				String path = file.getAbsolutePath();
				path = XML.stripExtension(path) + "." + ext; //$NON-NLS-1$
				file = new File(path);
			}
			if (!canWrite(file)) {
				return null;
			}
			return file;
		}
		return null;
	}

	/**
	 * Determines if a file can be written. If the file exists, the user is prompted
	 * for approval to overwrite.
	 *
	 * @param file the file to check
	 * @return true if the file can be written
	 */
	public static boolean canWrite(File file) {
		if (OSPRuntime.isJS)
			return true;
		if (file.exists() && !file.canWrite()) {
			JOptionPane.showMessageDialog(null, ControlsRes.getString("Dialog.ReadOnly.Message"), //$NON-NLS-1$
					ControlsRes.getString("Dialog.ReadOnly.Title"), //$NON-NLS-1$
					JOptionPane.PLAIN_MESSAGE);
			return false;
		}
		if (file.exists()) {
			int selected = JOptionPane.showConfirmDialog(null, "\"" + file.getName() + "\" " //$NON-NLS-1$ //$NON-NLS-2$
					+ TrackerRes.getString("TrackerIO.Dialog.ReplaceFile.Message"), //$NON-NLS-1$
					TrackerRes.getString("TrackerIO.Dialog.ReplaceFile.Title"), //$NON-NLS-1$
					JOptionPane.YES_NO_OPTION);
			if (selected != JOptionPane.YES_OPTION) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns a video from a specified path. May return null. Overrides VideoIO
	 * method.
	 *
	 * @param path    the path
	 * @param vidType a requested video type (may be null)
	 * @return the video
	 */
	private static Video getTrackerVideo(String path, VideoType vidType) {
		boolean logConsole = OSPLog.isConsoleMessagesLogged();
		if (!Tracker.warnXuggleError)
			OSPLog.setConsoleMessagesLogged(false);
		Video video = getVideo(path, vidType);
		OSPLog.setConsoleMessagesLogged(logConsole);
		return video;
	}

	/**
	 * Loads data or a video from a specified path into a TrackerPanel.
	 *
	 * @param path          the absolute path of a file or url
	 * @param existingPanel a TrackerPanel to load (may be null)
	 * @param frame         the frame for the TrackerPanel
	 * @param vidType       a preferred VideoType (may be null)
	 * @param desktopFiles  a list of HTML and/or PDF files to open on the desktop
	 *                      (may be null)
	 */
	private static void openTabPath(String path, TrackerPanel existingPanel, TFrame frame, VideoType vidType,
			ArrayList<String> desktopFiles, Runnable whenDone) {
		OSPLog.debug("TrackerIO openTabPath " + path); //$NON-NLS-1$
		new AsyncLoad(path, existingPanel, frame, vidType, desktopFiles, whenDone).execute();//Synchronously(); // for now
	}

	/**
	 * Loads data or a video from a specified file into a new TrackerPanel. If file
	 * is null, a file chooser is displayed.
	 *
	 * @param file  the file to be loaded (may be null)
	 * @param frame the frame for the TrackerPanel
	 */
	public static void openTabFile(File file, final TFrame frame) {
		OSPLog.debug("TrackerIO openTabFile " + file); //$NON-NLS-1$
		openTabFileAsync(file, frame, null);
	}

	public static void openTabFileAsync(File file, final TFrame frame, Runnable whenDone) {
		if (file == null) {
			getChooserFilesAsync("open", new Function<File[], Void>() {

				@Override
				public Void apply(File[] files) {
					File file = null;
					if (files != null) {
						file = files[0];
					}
					if (file == null) {
						OSPLog.finer("no file to open"); //$NON-NLS-1$
					} else {
						openTabFileAsyncFinally(frame, file, null);
					}
					return null;
				}

			});
		} else {
			openTabFileAsyncFinally(frame, file, null);
		}
	}

	static protected void openTabFileAsyncFinally(TFrame frame, File file, VideoType selectedType) {
		frame.loadedFiles.clear();
		final String path = XML.getAbsolutePath(file);
		final VideoType vidType = selectedType;

		// open all files in Tracker
		run("openTabPath", new Runnable() {
			@Override
			public void run() {
				openTabPath(path, null, frame, vidType, null, NULL_RUNNABLE);
			}
		});
	}
	
	/**
	 * Returns a clean TrackerPanel.
	 * Uses the blank untitled TrackerPanel in frame tab 0 if it is unchanged
	 *
	 * @param frame
	 * @return a clean TrackerPanel.
	 */
	static private TrackerPanel getCleanTrackerPanel(TFrame frame) {
		if (frame.getTabCount() > 0) {
			TrackerPanel existingPanel = frame.getTrackerPanel(0);
			String title = frame.tabbedPane.getTitleAt(0);
			if (title.equals(TrackerRes.getString("TrackerPanel.NewTab.Name")) //$NON-NLS-1$
			&& !existingPanel.changed) {
				return existingPanel;
			}
		}
		return new TrackerPanel();
	}

	/**
	 * Loads data or a video from a specified url into a new TrackerPanel.
	 *
	 * @param url   the url to be loaded
	 * @param frame the frame for the TrackerPanel
	 */
	public static void open(URL url, final TFrame frame) {
		if (url == null) {
			return;
		}
		final String path = url.toExternalForm();
		OSPLog.debug("TrackerIO opening URL"); //$NON-NLS-1$
		open(path, frame);
	}

	/**
	 * Loads a set of trk, trz, zip, or video files into one or more new TrackerPanels (tabs).
	 *
	 * @param uriPaths     an array of URL paths to be loaded
	 * @param frame        the frame for the TrackerPanels
	 * @param desktopFiles supplemental HTML and PDF files to load on the desktop
	 * @param trzPath      path to TRZ file, if that is the source
	 */
	public static void openCollection(final Collection<String> uriPaths, final TFrame frame,
			final ArrayList<String> desktopFiles, String trzPath) {
		if (uriPaths == null || uriPaths.isEmpty()) {
			return;
		}
		frame.loadedFiles.clear();
		Runnable whenDone = (trzPath != null && OSPRuntime.autoAddLibrary ? new Runnable() {

			@Override
			public void run() {
				addToLibrary(frame, trzPath);
			}

		} : null);
		
		// open in separate background thread if flagged
		run("tabOpener", new Runnable() {
			@Override
			public void run() {
				for (String uriPath : uriPaths) {
					OSPLog.debug("TrackerIO opening URL " + uriPath); //$NON-NLS-1$
					openTabPath(uriPath, null, frame, null, desktopFiles, whenDone);
				}
			}
		});
	}

	private static void run(String name, Runnable r) {
		if (loadInSeparateThread) {
			Thread t = new Thread(r);
			t.setName(name);
			t.setPriority(Thread.NORM_PRIORITY);
			t.setDaemon(true);
			t.start();
		} else {
			r.run();
		}
	}

	/**
	 * Loads data or a video from a specified path into a new TrackerPanel.
	 *
	 * @param path  the path
	 * @param frame the frame for the TrackerPanel
	 */
	public static void open(final String path, final TFrame frame) {
		frame.loadedFiles.clear();
		OSPLog.debug("TrackerIO open " + path); //$NON-NLS-1$
    openTabPath(path, null, frame, null, null, new Runnable() {

			@Override
			public void run() {
				if(trzFileFilter.accept(new File(path), false))
					addToLibrary(frame, path);
			}
        	
    });
	}

	private static void addToLibrary(TFrame frame, String path) {
		// also open TRZ files in library browser
		// BH! Q: this was effectively TRUE -- "any directory is OK" why?
		run ("addToLibrary", new Runnable() {
			@Override
			public void run() {
				OSPLog.debug("skipping TrackerIO addToLibrary " + path); //$NON-NLS-1$
				
				if (true) return;
				
				frame.getLibraryBrowser().open(path);
//			      frame.getLibraryBrowser().setVisible(true); 
				Timer timer = new Timer(1000, new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						LibraryTreePanel treePanel = frame.getLibraryBrowser().getSelectedTreePanel();
						if (treePanel != null) {
							treePanel.refreshSelectedNode();
						}
					}
				});
				timer.setRepeats(false);
				timer.start();
			}
		});
	}

	/**
	 * Imports xml data into a tracker panel from a file selected with a chooser.
	 * The user selects the elements to import with a ListChooser.
	 *
	 * @param trackerPanel the tracker panel
	 * @return the file
	 */
	public static File importFile(TrackerPanel trackerPanel) {
		File[] files = getChooserFiles("import file"); //$NON-NLS-1$
		if (files == null) {
			return null;
		}
		File file = files[0];
		OSPLog.fine("importing from " + file); //$NON-NLS-1$
		XMLControlElement control = new XMLControlElement(file.getAbsolutePath());
		Class<?> type = control.getObjectClass();
		if (TrackerPanel.class.equals(type)) {
			// choose the elements and load the tracker panel
			choose(trackerPanel, control, false, new Runnable() {

				@Override
				public void run() {
					trackerPanel.changed = true;
					control.loadObject(trackerPanel);
				}

			});
		} else {
			JOptionPane.showMessageDialog(trackerPanel.getTFrame(),
					TrackerRes.getString("TrackerPanel.Dialog.LoadFailed.Message") //$NON-NLS-1$
							+ " " + XML.getName(XML.getAbsolutePath(file)), //$NON-NLS-1$
					TrackerRes.getString("TrackerPanel.Dialog.LoadFailed.Title"), //$NON-NLS-1$
					JOptionPane.WARNING_MESSAGE);
			return null;
		}
		TTrackBar.refreshMemoryButton();
		return file;
	}

	/**
	 * Saves a video to a file by copying the original. If the file is null, a
	 * fileChooser is used to pick one.
	 *
	 * @param trackerPanel the tracker panel with the video
	 * @return the saved file, or null if not saved
	 */
	public static File saveVideo(File file, TrackerPanel trackerPanel) {
		Video video = trackerPanel.getVideo();
		if (video == null)
			return null;
		if (video instanceof ImageVideo) {
			boolean saved = ((ImageVideo) video).saveInvalidImages();
			if (!saved)
				return null;
		}
		String source = (String) video.getProperty("absolutePath"); //$NON-NLS-1$
		String extension = XML.getExtension(source);
		if (file == null) {
			File target = TrackerIO.getChooserFileForExtension(extension);
			if (target == null)
				return null;
			return saveVideo(target, trackerPanel);
		}
		boolean success = ResourceLoader.copyAllFiles(new File(source), file);
		if (success) {
			Tracker.addRecent(XML.getAbsolutePath(file), false); // add at beginning
			TMenuBar.refreshMenus(trackerPanel, TMenuBar.REFRESH_TRACKERIO_SAVEVIDEO);
			return file;
		}
		return null;
	}

	/**
	 * Imports chooser-selected video to the specified tracker panel.
	 *
	 * @param trackerPanel the tracker panel
	 */
	public static void importVideo(final TrackerPanel trackerPanel, Runnable whenDone) {
		JFileChooser chooser = getChooser();
		chooser.setDialogTitle(TrackerRes.getString("TrackerIO.Dialog.ImportVideo.Title")); //$NON-NLS-1$
		// 2020.04.03 DB changed chooser to async
		getChooserFilesAsync("open video", new Function<File[], Void>() {//$NON-NLS-1$

			@Override
			public Void apply(File[] files) {
				final File file = (files == null ? null : files[0]);
				if (file != null) {
					run("importVideo", new Runnable() {
						@Override
						public void run() {
							TrackerIO.importVideo(file, trackerPanel, null, whenDone);
						}
					});
				}
				return null;
			}

		});
//    File[] files = getChooserFiles("open video"); //$NON-NLS-1$
//    if (files==null || files.length==0) {
//      return;
//    }
//    // open in separate background thread if flagged
//    final File theFile = files[0];
//    Runnable importVideoRunner = new Runnable() {
//			public void run() {
//				TrackerIO.importVideo(theFile, trackerPanel, null);
//				OSPLog.debug("TrackerIO completed importing file " + theFile); //$NON-NLS-1$
//			}
//	    };
//    if (loadInSeparateThread) {
//      Thread importVideoOpener = new Thread(importVideoRunner);
//      importVideoOpener.setName("importVideo");
//      
//      importVideoOpener.setPriority(Thread.NORM_PRIORITY);
//      importVideoOpener.setDaemon(true);
//      importVideoOpener.start(); 
//    }
//    else importVideoRunner.run();
	}

	/**
	 * Imports a video file to the specified tracker panel.
	 *
	 * @param file         the video file
	 * @param trackerPanel the tracker panel
	 * @param vidType      the preferred video type (may be null)
	 */
	public static void importVideo(File file, TrackerPanel trackerPanel, VideoType vidType, Runnable whenDone) {
		String path = XML.getAbsolutePath(file);
		OSPLog.debug("TrackerIO importing file: " + path); //$NON-NLS-1$
		TFrame frame = trackerPanel.getTFrame();
		frame.loadedFiles.clear();
		openTabPath(path, trackerPanel, frame, vidType, null, whenDone);
	}

	/**
	 * Checks for video frames with durations that vary from the mean.
	 * 
	 * @param trackerPanel         the TrackerPanel to check
	 * @param tolerance            the unacceptable variation limit
	 * @param showDialog           true to display the results in a dialog
	 * @param onlyIfFound          true to display the dialog only if problems are
	 *                             found
	 * @param showSetDefaultButton true to show the "Don't show again" button
	 * @return an array of frames with odd durations
	 */
	public static ArrayList<Integer> findBadVideoFrames(TrackerPanel trackerPanel, double tolerance, boolean showDialog,
			boolean onlyIfFound, boolean showSetDefaultButton) {
		ArrayList<Integer> outliers = new ArrayList<Integer>();
		Video video = trackerPanel.getVideo();
		if (video == null)
			return outliers;
		double dur = video.getDuration();
		boolean done = false;
		while (!done) {
			int i = 0;
			double frameDur = dur / (video.getFrameCount() - outliers.size());
			for (; i < video.getFrameCount(); i++) {
				double err = Math.abs(frameDur - video.getFrameDuration(i)) / frameDur;
				if (err > tolerance && !outliers.contains(i)) {
					dur -= video.getFrameDuration(i);
					outliers.add(i);
					break;
				}
			}
			done = (i == video.getFrameCount());
		}
		if (outliers.contains(video.getFrameCount() - 1)) {
			outliers.remove(new Integer(video.getFrameCount() - 1));
		}
		if (showDialog) {
			NumberFormat format = NumberFormat.getInstance();
			String message = TrackerRes.getString("TrackerIO.Dialog.DurationIsConstant.Message"); //$NON-NLS-1$
			int messageType = JOptionPane.INFORMATION_MESSAGE;
			if (outliers.isEmpty() && onlyIfFound) {
				return outliers;
			}
			if (!outliers.isEmpty()) {
				messageType = JOptionPane.WARNING_MESSAGE;
				// get last bad frame
				int last = outliers.get(outliers.size() - 1);

				// find longest section of good frames
				int maxClear = -1;
				int start = 0, end = 0;
				int prevBadFrame = -1;
				for (Integer i : outliers) {
					int clear = i - prevBadFrame - 2;
					if (clear > maxClear) {
						start = prevBadFrame + 1;
						end = i - 1;
						maxClear = clear;
						prevBadFrame = i;
					}
				}
				VideoClip clip = trackerPanel.getPlayer().getVideoClip();
				if (clip.getEndFrameNumber() - last - 1 > maxClear) {
					start = last + 1;
					end = clip.getEndFrameNumber();
				}
				// assemble message
				format.setMaximumFractionDigits(2);
				format.setMinimumFractionDigits(2);
				message = TrackerRes.getString("TrackerIO.Dialog.DurationVaries.Message1"); //$NON-NLS-1$
				message += " " + (int) (tolerance * 100) + "%."; //$NON-NLS-1$ //$NON-NLS-2$
				message += "\n" + TrackerRes.getString("TrackerIO.Dialog.DurationVaries.Message2"); //$NON-NLS-1$//$NON-NLS-2$
				message += "\n" + TrackerRes.getString("TrackerIO.Dialog.DurationVaries.Message3"); //$NON-NLS-1$ //$NON-NLS-2$
				message += "\n\n" + TrackerRes.getString("TrackerIO.Dialog.DurationVaries.Message4"); //$NON-NLS-1$ //$NON-NLS-2$
				for (Integer i : outliers) {
					message += " " + i; //$NON-NLS-1$
					if (i < last)
						message += ","; //$NON-NLS-1$
				}
				message += "\n\n" + TrackerRes.getString("TrackerIO.Dialog.DurationVaries.Recommended") + ":  " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						+ TrackerRes.getString("TrackerIO.Dialog.DurationVaries.Start") + " " + start + ",  " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						+ TrackerRes.getString("TrackerIO.Dialog.DurationVaries.End") + " " + end + "\n "; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			} else { // all frames have identical durations
				format.setMaximumFractionDigits(2);
				format.setMinimumFractionDigits(2);
				double frameDur = trackerPanel.getPlayer().getClipControl().getMeanFrameDuration();
				message += ": " + format.format(frameDur) + "ms"; //$NON-NLS-1$ //$NON-NLS-2$
			}
			String close = TrackerRes.getString("Dialog.Button.OK"); //$NON-NLS-1$
			String dontShow = TrackerRes.getString("Tracker.Dialog.NoVideoEngine.Checkbox"); //$NON-NLS-1$
			String[] buttons = showSetDefaultButton ? new String[] { dontShow, close } : new String[] { close };
			int response = JOptionPane.showOptionDialog(theFrame, message,
					TrackerRes.getString("TrackerIO.Dialog.DurationVaries.Title"), //$NON-NLS-1$
					JOptionPane.YES_NO_OPTION, messageType, null, buttons, close);
			if (response >= 0 && response < buttons.length && buttons[response].equals(dontShow)) {
				Tracker.warnVariableDuration = false;
			}
		}
		return outliers;
	}

	/**
	 * Inserts chooser-selected images into an ImageVideo on a TrackerPanel.
	 *
	 * @param trackerPanel the TrackerPanel
	 * @param startIndex   the insertion index
	 * @return an array of inserted files
	 */
	public static File[] insertImagesIntoVideo(TrackerPanel trackerPanel, int startIndex) {
		JFileChooser chooser = getChooser();
		chooser.setDialogTitle(TrackerRes.getString("TrackerIO.Dialog.AddImage.Title")); //$NON-NLS-1$
		File[] files = getChooserFiles("insert image"); //$NON-NLS-1$
		return insertImagesIntoVideo(files, trackerPanel, startIndex);
	}

	/**
	 * Inserts file-based images into an ImageVideo on a TrackerPanel.
	 *
	 * @param files        array of image files
	 * @param trackerPanel the TrackerPanel
	 * @param startIndex   the insertion index
	 * @return an array of inserted files
	 */
	public static File[] insertImagesIntoVideo(File[] files, TrackerPanel trackerPanel, int startIndex) {
		if (files == null) {
			return null;
		}
		for (int i = 0; i < files.length; i++) {
			File file = files[i];
			// insert images in image video
			if (imageFileFilter.accept(file)) {
				try {
					ImageVideo imageVid = (ImageVideo) trackerPanel.getVideo();
					imageVid.insert(file.getAbsolutePath(), startIndex, files.length == 1);
					VideoClip clip = trackerPanel.getPlayer().getVideoClip();
					clip.setStepCount(imageVid.getFrameCount());
					trackerPanel.getPlayer().setStepNumber(clip.frameToStep(startIndex++));
				} catch (IOException ex) {
					ex.printStackTrace();
				}
			} else {
				String s = TrackerRes.getString("TrackerIO.Dialog.NotAnImage.Message1"); //$NON-NLS-1$
				if (i < files.length - 1) {
					s += XML.NEW_LINE + TrackerRes.getString("TrackerIO.Dialog.NotAnImage.Message2"); //$NON-NLS-1$
					int result = JOptionPane.showConfirmDialog(trackerPanel, "\"" + file + "\" " + s, //$NON-NLS-1$ //$NON-NLS-2$
							TrackerRes.getString("TrackerIO.Dialog.NotAnImage.Title"), //$NON-NLS-1$
							JOptionPane.WARNING_MESSAGE);
					if (result != JOptionPane.YES_OPTION) {
						if (i == 0)
							return null;
						File[] inserted = new File[i];
						System.arraycopy(files, 0, inserted, 0, i);
						TTrackBar.refreshMemoryButton();
						return inserted;
					}
				} else { // bad file is last one in array
					JOptionPane.showMessageDialog(trackerPanel.getTFrame(), "\"" + file + "\" " + s, //$NON-NLS-1$ //$NON-NLS-2$
							TrackerRes.getString("TrackerIO.Dialog.NotAnImage.Title"), //$NON-NLS-1$
							JOptionPane.WARNING_MESSAGE);
					if (i == 0)
						return null;
					File[] inserted = new File[i];
					System.arraycopy(files, 0, inserted, 0, i);
					TTrackBar.refreshMemoryButton();
					return inserted;
				}
			}
		}
		TTrackBar.refreshMemoryButton();
		return files;
	}

	/**
	 * Exports xml data from the specified tracker panel to a file selected with a
	 * chooser. Displays a dialog with choices of items to export.
	 * 
	 * @param trackerPanel the tracker panel
	 * @return the file
	 */
	public static void exportFile(TrackerPanel trackerPanel) {
		// create an XMLControl
		XMLControl control = new XMLControlElement(trackerPanel);
		choose(trackerPanel, control, true, new Runnable() {

			@Override
			public void run() {
				File[] files = getChooserFiles("export file"); //$NON-NLS-1$
				if (files == null) {
					return;
				}
				File file = files[0];
				if (!defaultXMLExt.equals(getExtension(file))) {
					String filename = XML.stripExtension(file.getPath());
					file = new File(filename + "." + defaultXMLExt); //$NON-NLS-1$
				}
				if (!canWrite(file))
					return;
				try {
					Writer writer = new FileWriter(file);
					control.write(writer);
					return;// file;
				} catch (IOException ex) {
					ex.printStackTrace();
				}
			}
		});
	}

	/**
	 * Displays a ListChooser with choices from the specified control. Modifies the
	 * control and returns true if the OK button is clicked.
	 * 
	 * @param trackerPanel
	 *
	 * @param control      the XMLControl
	 * @param dialog       the dialog
	 * @return <code>true</code> if OK button is clicked
	 */
	public static void choose(TrackerPanel trackerPanel, XMLControl control, boolean isExport, Runnable whenDone) {
		// create the lists

		ArrayList<XMLControl> choices = new ArrayList<XMLControl>();
		ArrayList<String> names = new ArrayList<String>();
		ArrayList<XMLControl> originals = new ArrayList<XMLControl>();
		ArrayList<XMLProperty> primitives = new ArrayList<XMLProperty>(); // non-object properties
		// add direct child controls except clipcontrol and toolbar
		XMLControl vidClipControl = null, vidControl = null;
		XMLControl[] children = control.getChildControls();
		for (int i = 0; i < children.length; i++) {
			String name = children[i].getPropertyName();
			if (name.equals("coords")) { //$NON-NLS-1$
				name = TrackerRes.getString("TMenuBar.MenuItem.Coords"); //$NON-NLS-1$
			} else if (name.equals("videoclip")) { //$NON-NLS-1$
				name = TrackerRes.getString("TMenuBar.MenuItem.VideoClip"); //$NON-NLS-1$
				vidControl = children[i].getChildControl("video"); //$NON-NLS-1$
				if (vidControl != null) {
					vidClipControl = children[i];
					originals.add(vidControl);
					choices.add(vidControl);
					names.add(name + " " + TrackerRes.getString("TrackerIO.Export.Option.WithoutVideo")); //$NON-NLS-1$//$NON-NLS-2$
					name = name + " " + TrackerRes.getString("TrackerIO.Export.Option.WithVideo"); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
			originals.add(children[i]);
			if (name.equals("clipcontrol")) //$NON-NLS-1$
				continue;
			if (name.equals("toolbar")) //$NON-NLS-1$
				continue;
			choices.add(children[i]);
			names.add(name);
		}
		// add track controls and gather primitives
		Iterator<XMLProperty> it = control.getPropsRaw().iterator();
		while (it.hasNext()) {
			XMLProperty prop = it.next();
			if ("tracks".indexOf(prop.getPropertyName()) != -1) { //$NON-NLS-1$
				children = prop.getChildControls();
				for (int i = 0; i < children.length; i++) {
					choices.add(children[i]);
					names.add(children[i].getPropertyName());
					originals.add(children[i]);
				}
			} else if (!prop.getPropertyType().equals("object")) { //$NON-NLS-1$
				primitives.add(prop);
			}
		}
		// show the dialog for user input and make changes if approved
		XMLControl vControl = vidControl, vClipControl = vidClipControl;
		ActionListener listener = new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				// remove primitives from control
				for (XMLProperty prop : primitives) {
					control.setValue(prop.getPropertyName(), null);
				}
				control.getPropertyContent().removeAll(primitives);
				// compare choices with originals and remove unwanted object content
				boolean removeVideo = false;
				for (XMLControl next : originals) {
					if (next == vControl) {
						removeVideo = choices.contains(next);
						continue;
					} else if (next == vClipControl) {
						if (!choices.contains(next)) {
							if (removeVideo) {
								// remove video from clip property
								XMLProperty prop = vControl.getParentProperty();
								vClipControl.setValue("video", null); //$NON-NLS-1$
								vClipControl.getPropertyContent().remove(prop);
							} else {
								// remove video clip property entirely
								XMLProperty prop = next.getParentProperty();
								control.setValue(prop.getPropertyName(), null);
								control.getPropertyContent().remove(prop);
							}
						}
						continue;
					} else if (!choices.contains(next)) {
						XMLProperty prop = next.getParentProperty();
						XMLProperty parent = prop.getParentProperty();
						if (parent == control) {
							control.setValue(prop.getPropertyName(), null);
						}
						parent.getPropertyContent().remove(prop);
					}
				}
				// if no tracks are selected, eliminate tracks property
				boolean deleteTracks = true;
				for (Object next : control.getPropertyContent()) {
					XMLProperty prop = (XMLProperty) next;
					if ("tracks".indexOf(prop.getPropertyName()) > -1) { //$NON-NLS-1$
						deleteTracks = prop.getChildControls().length == 0;
					}
				}
				if (deleteTracks) {
					control.setValue("tracks", null); //$NON-NLS-1$
				}
				whenDone.run();
			}

		};

		ListChooser dialog = (isExport ?
		// create a list chooser
				new ListChooser(TrackerRes.getString("TrackerIO.Dialog.Export.Title"), //$NON-NLS-1$
						TrackerRes.getString("TrackerIO.Dialog.Export.Message"), //$NON-NLS-1$
						trackerPanel, listener)
				: new ListChooser(TrackerRes.getString("TrackerIO.Dialog.Import.Title"), //$NON-NLS-1$
						TrackerRes.getString("TrackerIO.Dialog.Import.Message"), //$NON-NLS-1$
						trackerPanel, listener));

		dialog.choose(choices, names);
	}

	/**
	 * Copies an xml string representation of the specified object to the system
	 * clipboard.
	 *
	 * @param obj the object to copy
	 */
	public static void copyXML(Object obj) {
		XMLControl control = new XMLControlElement(obj);
		StringSelection data = new StringSelection(control.toXML());
		Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		clipboard.setContents(data, data);
	}

	/**
	 * Pastes a new object into the specified tracker panel from an xml string on
	 * the system clipboard.
	 *
	 * @param trackerPanel the tracker panel
	 */
	public static boolean pasteXML(TrackerPanel trackerPanel) {
		try {
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			Transferable data = clipboard.getContents(null);
			if (data == null)
				return false;
			XMLControl control = new XMLControlElement();
			control.readXML((String) data.getTransferData(DataFlavor.stringFlavor));
			Class<?> type = control.getObjectClass();
			if (control.failedToRead() || type == null) {
				return false;
			}
			if (TTrack.class.isAssignableFrom(type)) {
				TTrack track = (TTrack) control.loadObject(null);
				if (track != null) {
					trackerPanel.addTrack(track);
					trackerPanel.setSelectedTrack(track);
					return true;
				}
			} else if (VideoClip.class.isAssignableFrom(type)) {
				VideoClip clip = (VideoClip) control.loadObject(null);
				if (clip != null) {
					VideoClip prev = trackerPanel.getPlayer().getVideoClip();
					XMLControl state = new XMLControlElement(prev);
					// make new XMLControl with no stored object
					state = new XMLControlElement(state.toXML());
					trackerPanel.getPlayer().setVideoClip(clip);
					Undo.postVideoReplace(trackerPanel, state);
					return true;
				}
			} else if (ImageCoordSystem.class.isAssignableFrom(type)) {
				XMLControl state = new XMLControlElement(trackerPanel.getCoords());
				control.loadObject(trackerPanel.getCoords());
				Undo.postCoordsEdit(trackerPanel, state);
				return true;
			}
			if (TrackerPanel.class.isAssignableFrom(type)) {
				control.loadObject(trackerPanel);
				return true;
			}
		} catch (Exception ex) {
		}
		return false;
	}

	/**
	 * Copies data in the specified datatable to the system clipboard.
	 *
	 * @param table       the datatable to copy
	 * @param asFormatted true to retain table formatting
	 * @param header      the table header
	 */
	public static void copyTable(DataTable table, boolean asFormatted, String header) {
		Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		StringBuffer buf = getData(table, asFormatted);
		// replace spaces with underscores in header (must be single string)
		header = header.replace(' ', '_');
		if (!header.endsWith(XML.NEW_LINE))
			header += XML.NEW_LINE;
		StringSelection stringSelection = new StringSelection(header + buf.toString());
		clipboard.setContents(stringSelection, stringSelection);
		dataCopiedToClipboard = true;
	}

	/**
	 * Copies the specified image to the system clipboard.
	 *
	 * @param image the image to copy
	 */
	public static void copyImage(Image image) {
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new TransferImage(image), null);
	}

	/**
	 * Returns the image on the clipboard, if any.
	 *
	 * @return the image, or null if none found
	 */
	public static Image getClipboardImage() {
		Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
		try {
			if (t != null && t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
				Image image = (Image) t.getTransferData(DataFlavor.imageFlavor);
				return image;
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return null;
	}

	/**
	 * Gets the data selected by the user in a datatable. This method is modified
	 * from the org.opensourcephysics.display.DataTableFrame getSelectedData method.
	 *
	 * @param table       the datatable containing the data
	 * @param asFormatted true to retain table formatting
	 * @return a StringBuffer containing the data.
	 */
	public static StringBuffer getData(DataTable table, boolean asFormatted) {
		StringBuffer buf = new StringBuffer();
		// get selected data
		int[] selectedRows = table.getSelectedRows();
		int[] selectedColumns = table.getSelectedColumns();
		// if no data is selected, select all
		int[] restoreRows = null;
		int[] restoreColumns = null;
		if (selectedRows.length == 0) {
			table.selectAll();
			restoreRows = selectedRows;
			restoreColumns = selectedColumns;
			selectedRows = table.getSelectedRows();
			selectedColumns = table.getSelectedColumns();
		}
		// copy column headings
		for (int j = 0; j < selectedColumns.length; j++) {
			// ignore row heading
			if (table.isRowNumberVisible() && selectedColumns[j] == 0)
				continue;
			buf.append(table.getColumnName(selectedColumns[j]));
			if (j < selectedColumns.length - 1)
				buf.append(delimiter); // add delimiter after each column except the last
		}
		buf.append(XML.NEW_LINE);
		java.text.DecimalFormat nf = (DecimalFormat) NumberFormat.getInstance();
		nf.applyPattern("0.000000000E0"); //$NON-NLS-1$
		nf.setDecimalFormatSymbols(OSPRuntime.getDecimalFormatSymbols());
		java.text.DateFormat df = java.text.DateFormat.getInstance();
		for (int i = 0; i < selectedRows.length; i++) {
			for (int j = 0; j < selectedColumns.length; j++) {
				int temp = table.convertColumnIndexToModel(selectedColumns[j]);
				if (table.isRowNumberVisible()) {
					if (temp == 0) { // don't copy row numbers
						continue;
					}
				}
				Object value = null;
				if (asFormatted) {
					value = table.getFormattedValueAt(selectedRows[i], selectedColumns[j]);
				} else {
					value = table.getValueAt(selectedRows[i], selectedColumns[j]);
					if (value != null) {
						if (value instanceof Number) {
							value = nf.format(value);
						} else if (value instanceof java.util.Date) {
							value = df.format(value);
						}
					}
				}
				if (value != null) {
					buf.append(value);
				}
				if (j < selectedColumns.length - 1)
					buf.append(delimiter); // add delimiter after each column except the last
			}
			buf.append(XML.NEW_LINE); // new line after each row
		}
		if (restoreRows != null) {
			// restore previous selection state
			table.clearSelection();
			for (int row : restoreRows)
				table.addRowSelectionInterval(row, row);
			for (int col : restoreColumns)
				table.addColumnSelectionInterval(col, col);
		}
		return buf;
	}

	/**
	 * Sets the delimiter for copied or exported data
	 *
	 * @param delimiter the delimiter
	 */
	public static void setDelimiter(String delimiter) {
		if (delimiter != null)
			TrackerIO.delimiter = delimiter;
	}

	/**
	 * Gets the delimiter for copied or exported data
	 *
	 * @return the delimiter
	 */
	public static String getDelimiter() {
		return delimiter;
	}

	/**
	 * Adds a custom delimiter to the collection of delimiters
	 *
	 * @param custom the delimiter to add
	 */
	public static void addCustomDelimiter(String custom) {
		if (!delimiters.values().contains(custom)) { // don't add a standard delimiter
			// by default, use delimiter itself for key (used for display purposes--could be
			// description)
			TrackerIO.customDelimiters.put(custom, custom);
		}
	}

	/**
	 * Removes a custom delimiter from the collection of delimiters
	 *
	 * @param custom the delimiter to remove
	 */
	public static void removeCustomDelimiter(String custom) {
		if (TrackerIO.getDelimiter().equals(custom))
			setDelimiter(TrackerIO.defaultDelimiter);
		String selected = null;
		for (String key : customDelimiters.keySet()) {
			if (customDelimiters.get(key).equals(custom))
				selected = key;
		}
		if (selected != null)
			customDelimiters.remove(selected);
	}

	/**
	 * Finds page view file paths in an XMLControl and maps the page view path to
	 * the URL path of the file. If the page view path refers to a file inside a
	 * trk, zip or jar file, then all files in the jar are extracted and the URL
	 * path points to the extracted HTML file. This ensures that the HTML page can
	 * be opened on the desktop.
	 */
	private static void findPageViewFiles(XMLControl control, Map<String, String> pageViewFiles) {
		// extract page view filenames from control xml
		String xml = control.toXML();
		// basic unit is a tab with title and text
		String token = "PageTView$TabView"; //$NON-NLS-1$
		int j = xml.indexOf(token);
		while (j > -1) { // found page view tab
			xml = xml.substring(j + token.length());
			// get text and check if it is a loadable path
			token = "<property name=\"text\" type=\"string\">"; //$NON-NLS-1$
			j = xml.indexOf(token);
			String path = xml.substring(j + token.length());
			j = path.indexOf("</property>"); //$NON-NLS-1$
			path = path.substring(0, j);
			if (path.endsWith(".html") || path.endsWith(".htm")) { //$NON-NLS-1$ //$NON-NLS-2$
				Resource res = ResourceLoader.getResource(path);
				if (res != null) {
					// found an HTML file, so add it to the map
					String urlPath = res.getURL().toExternalForm();
					if (OSPRuntime.unzipFiles) {
						String zipPath = ResourceLoader.getNonURIPath(res.getAbsolutePath());
						int n = zipPath.indexOf("!/"); //$NON-NLS-1$
						// extract files from jar, zip or trz files into temp directory
						if (n > 0) {
							File target = new File(OSPRuntime.tempDir); // $NON-NLS-1$
							zipPath = zipPath.substring(0, n);
							ResourceLoader.unzip(zipPath, target, true); // overwrite
							target = new File(target, path);
							if (target.exists()) {
								res = ResourceLoader.getResource(target.getAbsolutePath());
								urlPath = res.getURL().toExternalForm();
							} else {
								path = null;
							}
						}
					}
					if (path != null) {
						pageViewFiles.put(path, urlPath);
					}
				}
			}

			// look for the next tab
			token = "PageTView$TabView"; //$NON-NLS-1$
			j = xml.indexOf(token);
		}

	}

	/**
	 * ComponentImage class for printing and copying images of components. This is
	 * adapted from code in SnapshotTool and DrawingPanel
	 */
	static class ComponentImage implements Printable {
		private BufferedImage image;
		Component c;

		ComponentImage(Component comp) {
			c = comp;
			if (comp instanceof JFrame)
				comp = ((JFrame) comp).getContentPane();
			else if (comp instanceof JDialog)
				comp = ((JDialog) comp).getContentPane();
			int w = (comp.isVisible()) ? comp.getWidth() : comp.getPreferredSize().width;
			int h = (comp.isVisible()) ? comp.getHeight() : comp.getPreferredSize().height;
			image = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
			if (comp instanceof Renderable)
				image = ((Renderable) comp).render(image);
			else {
				java.awt.Graphics g = image.getGraphics();
				comp.paint(g);
				g.dispose();
			}
		}

		BufferedImage getImage() {
			return image;
		}

		void copyToClipboard() {
			copyImage(image);
		}

		/** Implements Printable */
		public void print() {
			PrinterJob printerJob = PrinterJob.getPrinterJob();
			PageFormat format = new PageFormat();
			java.awt.print.Book book = new java.awt.print.Book();
			book.append(this, format);
			printerJob.setPageable(book);
			if (printerJob.printDialog()) {
				try {
					printerJob.print();
				} catch (PrinterException pe) {
					JOptionPane.showMessageDialog(c, TrackerRes.getString("TActions.Dialog.PrintError.Message"), //$NON-NLS-1$
							TrackerRes.getString("TActions.Dialog.PrintError.Title"), //$NON-NLS-1$
							JOptionPane.ERROR_MESSAGE);
				}
			}

		}

		/**
		 * Implements Printable.
		 * 
		 * @param g          the printer graphics
		 * @param pageFormat the format
		 * @param pageIndex  the page number
		 * @return status code
		 */
		@Override
		public int print(Graphics g, PageFormat pageFormat, int pageIndex) {
			if (pageIndex >= 1) { // only one page available
				return Printable.NO_SUCH_PAGE;
			}
			if (g == null) {
				return Printable.NO_SUCH_PAGE;
			}
			Graphics2D g2 = (Graphics2D) g;
			double scalex = pageFormat.getImageableWidth() / image.getWidth();
			double scaley = pageFormat.getImageableHeight() / image.getHeight();
			double scale = Math.min(scalex, scaley);
			scale = Math.min(scale, 1.0); // don't magnify images--only reduce if nec
			g2.translate((int) pageFormat.getImageableX(), (int) pageFormat.getImageableY());
			g2.scale(scale, scale);
			g2.drawImage(image, 0, 0, null);
			return Printable.PAGE_EXISTS;
		}

	}

	public static class AsyncLoad extends AsyncSwingWorker implements TrackerMonitor {

		private String path;
		private TrackerPanel existingPanel;
		private TFrame frame;
		private VideoType vidType;
		private ArrayList<String> desktopFiles;
		private Runnable whenDone;
		private boolean panelChanged;
		private TrackerPanel trackerPanel;
		private String rawPath;
		private String nonURIPath;
		private XMLControlElement control;
		private String xmlPath;

		private static final int TYPE_UNK = 0;
		private static final int TYPE_ZIP = 1;
		private static final int TYPE_PANEL = 2;
		private static final int TYPE_FRAME = 3;
		private static final int TYPE_VIDEO = 4;

		private int type = TYPE_UNK;
		private int frameCount;
		private String name;
		private String title;    // BH TODO
		private boolean stopped; // BH TODO
		private long t0;

		public AsyncLoad(String path, TrackerPanel existingPanel, TFrame frame, VideoType vidType,
				ArrayList<String> desktopFiles, Runnable whenDone) {
			super(frame, path, (whenDone == null ? 0 : 10), 0, 100);
			this.path = this.name = path;
			isAsync = (delayMillis > 0);
			this.existingPanel = existingPanel;
			this.frame = frame;
			this.vidType = vidType;
			this.desktopFiles = desktopFiles;
			this.whenDone = whenDone;
			monitors.add(this);
			

			if (type == TYPE_ZIP)
				frame.holdPainting(true);
			OSPLog.debug(Performance.timeCheckStr("TrackerPanel.AsyncLoad start " + path, Performance.TIME_MARK));
			t0 = Performance.now(0);
		}

		@Override
		public int doInBackgroundAsync(int progress) {
			OSPLog.debug(Performance.timeCheckStr("TrackerPanel.AsyncLoad " + type + " start " + progress + " " + path, Performance.TIME_MARK));
			switch (type) {
			case TYPE_ZIP:
				progress = openTabPathZip(progress);
				break;
			case TYPE_PANEL:
				progress = openTabPathPanel(progress);
				break;
			case TYPE_FRAME:
				progress = openTabPathFrame(progress);
				break;
			case TYPE_VIDEO:
				progress = openTabPathVideo(progress);
				break;
			default:
				return 100;
			}
			OSPLog.debug(Performance.timeCheckStr("TrackerPanel.AsyncLoad " + type + " end " + progress + " " + path, Performance.TIME_MARK));
			return progress;
		}

		@Override
		public void doneAsync() {
			doneLoading();
		}

		@Override
		public void initAsync() {

			
			rawPath = path;
			path = ResourceLoader.getURIPath(path);

			isffmpegError = false;
			theFrame = frame;
			setCanceled(false);
			// prevent circular references when loading tabsets
			nonURIPath = ResourceLoader.getNonURIPath(path);
			if (rawPath.startsWith("//") && nonURIPath.startsWith("/") && !nonURIPath.startsWith("//")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				nonURIPath = "/" + nonURIPath; //$NON-NLS-1$
			if (frame.loadedFiles.contains(nonURIPath)) {
				OSPLog.debug("TrackerIO path already loaded " + nonURIPath); //$NON-NLS-1$
				return;
			}
			frame.loadedFiles.add(nonURIPath);
			if (!ResourceLoader.isHTTP(path))
				path = nonURIPath;

			trackerPanel = existingPanel == null ? getCleanTrackerPanel(frame) : existingPanel;

			panelChanged = trackerPanel.changed;
//			// create progress monitor
//			monitorDialog = new MonitorDialog(frame, path);
//			monitorDialog.setVisible(true);
//			monitors.add(monitorDialog);

			File testFile = new File(XML.getName(path));
			// BH note - this file is not likely to exist without its pathname.
			// changed to just check extensions, not if directory (which requires an
			// existence check)
			if (videoFileFilter.accept(testFile, false)) {
				type = TYPE_VIDEO;
				return;
			}
			// load data from zip, trz or trk file
			if (zipFileFilter.accept(testFile, false) || trzFileFilter.accept(testFile, false)) {
				type = TYPE_ZIP;
				return;
			}

			// load data from TRK file
			control = new XMLControlElement();
			xmlPath = control.read(path);
			if (isCanceled()) {
				cancelAsync();
				return;
			}
			Class<?> ctype = control.getObjectClass();

			if (TrackerPanel.class.isAssignableFrom(ctype)) {
				type = TYPE_PANEL;
				return;
			}

			if (TFrame.class.isAssignableFrom(ctype)) {
				type = TYPE_FRAME;
				return;
			}
			// FAILURE
			if (control.failedToRead()) {
				JOptionPane.showMessageDialog(trackerPanel.getTFrame(),
						MediaRes.getString("VideoIO.Dialog.BadFile.Message") + //$NON-NLS-1$
								ResourceLoader.getNonURIPath(path));
			} else {
				JOptionPane.showMessageDialog(trackerPanel.getTFrame(), "\"" + XML.getName(path) + "\" " + //$NON-NLS-1$ //$NON-NLS-2$
						MediaRes.getString("VideoIO.Dialog.XMLMismatch.Message"), //$NON-NLS-1$
						MediaRes.getString("VideoIO.Dialog.XMLMismatch.Title"), //$NON-NLS-1$
						JOptionPane.WARNING_MESSAGE);
			}
			setCanceled(true);
			cancelAsync();
		}

		private int openTabPathFrame(int progress) {
			control.loadObject(frame);
			Tracker.addRecent(ResourceLoader.getNonURIPath(XML.forwardSlash(rawPath)), false); // add at beginning
			trackerPanel = frame.getTrackerPanel(frame.getSelectedTab());
			TMenuBar.refreshMenus(trackerPanel, TMenuBar.REFRESH_TRACKERIO_OPENFRAME);
			return 100;
		}

		private int openTabPathPanel(int progress) {
			XMLControl child = control.getChildControl("videoclip"); //$NON-NLS-1$
			if (child != null) {
				int count = child.getInt("video_framecount"); //$NON-NLS-1$
				child = child.getChildControl("video"); //$NON-NLS-1$
				if (child != null) {
					String vidPath = child.getString("path"); //$NON-NLS-1$
//					monitorDialog.setName(vidPath);
//					monitorDialog.setFrameCount(count);
				}
			}

			trackerPanel = (TrackerPanel) control.loadObject(trackerPanel, (Object) frame);
			trackerPanel.setIgnoreRepaint(true);

			// find page view files and add to TrackerPanel.pageViewFilePaths
			findPageViewFiles(control, trackerPanel.pageViewFilePaths);

			if (desktopFiles != null) {
				for (String s : desktopFiles) {
					trackerPanel.supplementalFilePaths.add(s);
				}
			}
			boolean isZippedTRK = xmlPath != null
					&& (xmlPath.contains(".zip!") || xmlPath.contains(".trz!") || xmlPath.contains(".jar!")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (isZippedTRK) {
				String parent = xmlPath.substring(0, xmlPath.indexOf("!")); //$NON-NLS-1$
				parent = ResourceLoader.getNonURIPath(parent); // strip protocol
				String parentName = XML.stripExtension(XML.getName(parent));
				String tabName = XML.stripExtension(XML.getName(xmlPath));
				if (tabName.startsWith(parentName) && parentName.length() + 1 < tabName.length()) {
					tabName = tabName.substring(parentName.length() + 1, tabName.length());
				}
				trackerPanel.openedFromPath = parent;
				trackerPanel.defaultFileName = tabName;

				String html = ResourceLoader.getString(parent + "!/html/" + parentName + "_info.html"); //$NON-NLS-1$ //$NON-NLS-2$
				if (html != null) {
					ArrayList<String[]> metadata = getMetadataFromHTML(html);
					for (int i = 0; i < metadata.size(); i++) {
						String[] meta = metadata.get(i);
						String key = meta[0];
						String value = meta[1];
						if (trackerPanel.author == null
								&& LibraryResource.META_AUTHOR.toLowerCase().contains(key.toLowerCase())) {
							trackerPanel.author = value;
						} else if (trackerPanel.contact == null
								&& LibraryResource.META_CONTACT.toLowerCase().contains(key.toLowerCase())) {
							trackerPanel.contact = value;
						}
					}
				}
			} else {
				trackerPanel.defaultFileName = XML.getName(path);
				trackerPanel.openedFromPath = path;
				trackerPanel.setDataFile(new File(ResourceLoader.getNonURIPath(path)));
			}

//			if (monitorDialog.isVisible())
//				monitorDialog.setProgress(80);
			if (isCanceled())
				return 100;
			frame.addTab(trackerPanel, null);
//			if (monitorDialog.isVisible())
//				monitorDialog.setProgress(90);
			frame.setSelectedTab(trackerPanel);
			frame.showTrackControl(trackerPanel);
			frame.showNotes(trackerPanel);
			trackerPanel.setIgnoreRepaint(false);
			frame.refresh();
			if (control.failedToRead()) {
				JOptionPane.showMessageDialog(trackerPanel.getTFrame(), "\"" + XML.getName(path) + "\" " + //$NON-NLS-1$ //$NON-NLS-2$
						TrackerRes.getString("TrackerIO.Dialog.ReadFailed.Message"), //$NON-NLS-1$
						TrackerRes.getString("TrackerIO.Dialog.ReadFailed.Title"), //$NON-NLS-1$
						JOptionPane.WARNING_MESSAGE);
			}
			return 100;
		}

		private int openTabPathZip(int progress) {
			// create progress monitor
			Map<String, String> pageViewTabs = new HashMap<String, String>(); // pageView tabs that display html files
			String name = XML.getName(ResourceLoader.getNonURIPath(path));
			// download web files to OSP cache
			boolean isWebPath = ResourceLoader.isHTTP(path);
			if (isWebPath) {
				File localFile = ResourceLoader.downloadToOSPCache(path, name, false);
				if (localFile != null && OSPRuntime.unzipFiles) {
					// set path to downloaded file
					path = localFile.toURI().toString();
					OSPLog.debug("TrackerIO downloaded zip file: " + path); //$NON-NLS-1$
				}
			}

			ArrayList<String> trkFiles = new ArrayList<String>(); // all trk files found in zip
			final ArrayList<String> htmlFiles = new ArrayList<String>(); // supplemental html files found in zip
			final ArrayList<String> pdfFiles = new ArrayList<String>(); // all pdf files found in zip
			final ArrayList<String> otherFiles = new ArrayList<String>(); // other files found in zip
			String trkForTFrame = null;

			// sort the zip file contents
			Map<String, ZipEntry> contents = ResourceLoader.getZipContents(path);
			for (String next : contents.keySet()) {
				if (next.endsWith(".trk")) { //$NON-NLS-1$
					String s = ResourceLoader.getURIPath(path + "!/" + next); //$NON-NLS-1$
					OSPLog.debug("TrackerIO found trk file " + s); //$NON-NLS-1$
					trkFiles.add(s);
				} else if (next.endsWith(".pdf")) { //$NON-NLS-1$
					pdfFiles.add(next);
				} else if (next.endsWith(".html") || next.endsWith(".htm")) { //$NON-NLS-1$ //$NON-NLS-2$
					// handle HTML info files (name "<zipname>_info")
					String baseName = XML.stripExtension(name);
					String nextName = XML.getName(next);
					if (XML.stripExtension(nextName).equals(baseName + "_info")) { //$NON-NLS-1$
						continue;
					}
					// add non-info html files to list
					htmlFiles.add(next);
				}
				// collect other files in top directory except thumbnails
				else if (next.indexOf("thumbnail") == -1 && next.indexOf("/") == -1) { //$NON-NLS-1$ //$NON-NLS-2$
					String s = ResourceLoader.getURIPath(path + "!/" + next); //$NON-NLS-1$
					OSPLog.debug("TrackerIO found other file " + s); //$NON-NLS-1$
					otherFiles.add(next);
				}
			}
			if (trkFiles.isEmpty() && pdfFiles.isEmpty() && htmlFiles.isEmpty() && otherFiles.isEmpty()) {
				String s = TrackerRes.getString("TFrame.Dialog.LibraryError.Message"); //$NON-NLS-1$
				JOptionPane.showMessageDialog(frame, s + " \"" + name + "\".", //$NON-NLS-1$ //$NON-NLS-2$
						TrackerRes.getString("TFrame.Dialog.LibraryError.Title"), //$NON-NLS-1$
						JOptionPane.WARNING_MESSAGE);
				return 100;
			}

			// find page view filenames in TrackerPanel xmlControls
			// also look for trk for TFrame
			if (!trkFiles.isEmpty()) {
				ArrayList<String> trkNames = new ArrayList<String>();
				for (String next : trkFiles) {
					trkNames.add(XML.stripExtension(XML.getName(next)));
					XMLControl control = new XMLControlElement(next);
					if (control.getObjectClassName().endsWith("TrackerPanel")) { //$NON-NLS-1$
						findPageViewFiles(control, pageViewTabs);
					} else if (trkForTFrame == null && control.getObjectClassName().endsWith("TFrame")) { //$NON-NLS-1$
						trkForTFrame = next;
					}
				}
				if (!htmlFiles.isEmpty()) {
					// remove page view HTML files
					String[] paths = htmlFiles.toArray(new String[htmlFiles.size()]);
					for (String htmlPath : paths) {
						boolean isPageView = false;
						for (String page : pageViewTabs.keySet()) {
							isPageView = isPageView || htmlPath.endsWith(page);
						}
						if (isPageView) {
							htmlFiles.remove(htmlPath);
						}
						// discard HTML <trkname>_info files
						for (String trkName : trkNames) {
							if (htmlPath.contains(trkName + "_info.")) { //$NON-NLS-1$
								htmlFiles.remove(htmlPath);
							}
						}
					}
				}
				if (trkForTFrame != null) {
					trkFiles.clear();
					trkFiles.add(trkForTFrame);
				}
			}

			// unzip pdf/html/other files into temp directory and open on desktop
			final ArrayList<String> tempFiles = new ArrayList<String>();
			if (!htmlFiles.isEmpty() || !pdfFiles.isEmpty() || !otherFiles.isEmpty()) {
				if (OSPRuntime.unzipFiles) {

					File temp = new File(OSPRuntime.tempDir); // $NON-NLS-1$
					Set<File> files = ResourceLoader.unzip(path, temp, true);
					for (File next : files) {
						next.deleteOnExit();
						// add PDF/HTML/other files to tempFiles
						String relPath = XML.getPathRelativeTo(next.getPath(), temp.getPath());
						if (pdfFiles.contains(relPath) || htmlFiles.contains(relPath) || otherFiles.contains(relPath)) {
							String tempPath = ResourceLoader.getURIPath(next.getAbsolutePath());
							tempFiles.add(tempPath);
						}
					}
				} else {
					tempFiles.addAll(htmlFiles);
					tempFiles.addAll(pdfFiles);
					tempFiles.addAll(otherFiles);
				}
				// open tempfiles on the desktop
				if (OSPRuntime.skipDisplayOfPDF) {
				} else {
					Runnable displayURLRunner = new Runnable() {
						@Override
						public void run() {
							for (String path : tempFiles) {
								OSPDesktop.displayURL(path);
							}
						}
					};
					Thread displayURLOpener = new Thread(displayURLRunner);
					displayURLOpener.setName("displayURLOpener");
					displayURLOpener.start();
				}
			}
			// load trk files into Tracker
			if (!isCanceled()) {
				openCollection(trkFiles, frame, tempFiles, path); // this also adds tempFile paths to trackerPanel
				// add TRZ, ZIP and JAR paths to recent files
				Tracker.addRecent(nonURIPath, false); // add at beginning
				return 100;
			}
			return 100;
		}

		private int openTabPathVideo(int progress) {
			trackerPanel.setTFrame(frame);
			OSPLog.debug("TrackerIO opening video path " + path); //$NON-NLS-1$
			// download web videos to the OSP cache
			if (ResourceLoader.isHTTP(path)) {
				String name = XML.getName(path);
				name = ResourceLoader.getNonURIPath(name);
				File localFile = ResourceLoader.downloadToOSPCache(path, name, false);
				if (localFile != null) {
					path = localFile.toURI().toString();
				}
			}

			// attempt to load video
			Video video = getTrackerVideo(path, vidType);
//			monitorDialog.stop();
			if (video == null || isCanceled()) {
				cancelAsync();
				// monitorDialog.close();
				return 100;
			}
			// if (monitorDialog.isVisible())
			// monitorDialog.setProgress(85);
			vidType = (VideoType) video.getProperty("video_type"); //$NON-NLS-1$
			OSPLog.finer(video.getProperty("path") + " opened as " + //$NON-NLS-1$ //$NON-NLS-2$
					vidType.getClass().getSimpleName() + " " + vidType.getDescription()); //$NON-NLS-1$
			if (isCanceled())
				return 100;

			frame.addTab(trackerPanel, null);
//			if (monitorDialog.isVisible())
//				monitorDialog.setProgress(95);
			JSplitPane pane = frame.getSplitPane(trackerPanel, 0);
			pane.setDividerLocation(frame.defaultRightDivider);
			// BH ?? TMenuBar.refreshMenus(trackerPanel, TMenuBar.REFRESH_BEFORESETVIDEO);
			trackerPanel.setVideo(video);
			// panel is changed if video imported into existing trackerPanel
			panelChanged = existingPanel != null;
			if (video.getFrameCount() == 1) {
				trackerPanel.getPlayer().getVideoClip().setStepCount(10);
			}
			// if new trackerPanel, move coords origin to center of video
			if (existingPanel == null) {
				ImageCoordSystem coords = trackerPanel.getCoords();
				coords.setAllOriginsXY(video.getWidth() / 2, video.getHeight() / 2);
			}
			TFrame.repaintT(trackerPanel);
			frame.setSelectedTab(trackerPanel);
//			monitorDialog.close();
			// check for video frames with durations that vary by 20% from average
			if (Tracker.warnVariableDuration)
				findBadVideoFrames(trackerPanel, defaultBadFrameTolerance, true, true, true);
			// show dialog only if
			// bad frames found,
			// and include
			// "don't show
			// again" button
			return 100;

		}

		private void doneLoading() {
//			monitorDialog.close();
			String path = XML.forwardSlash(rawPath);
			if (xmlPath != null && !xmlPath.contains(".zip!") && //$NON-NLS-1$
					!xmlPath.contains(".trz!") && //$NON-NLS-1$
					!xmlPath.contains(".jar!")) { //$NON-NLS-1$
				path = XML.forwardSlash(xmlPath);
				Tracker.addRecent(ResourceLoader.getNonURIPath(path), false); // add at beginning
			}
			
			trackerPanel.changed = panelChanged;
			TTrackBar.refreshMemoryButton();
			if (type == TYPE_PANEL) {
				frame.clearHoldPainting();
				trackerPanel.notifyLoadingComplete();
			}

			OSPLog.debug(Performance.timeCheckStr("TrackerPanel.AsyncLoad done " + path, Performance.TIME_MARK));
			OSPLog.debug("!!! " + Performance.now(t0) + " AyncLoad " + path);

			if (whenDone == null) {
			} else {
				SwingUtilities.invokeLater(new Runnable() {

					@Override
					public void run() {
						whenDone.run();
					}
				});
				
			}
			

		}

		@Override
		public void stop() {
			stopped = true;
			frame.clearHoldPainting();
		}

		@Override
		public void setFrameCount(int count) {
			frameCount = count;
		}

		@Override
		public void close() {
			cancelAsync();
			setProgress(100);
		}
		
		@Override 
		public void cancelAsync() {
			super.cancelAsync();
			frame.clearHoldPainting();
		}

		@Override
		public int getFrameCount() {
			return frameCount;
		}

		@Override
		public void restart() {
			setProgress(0);
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public void setTitle(String title) {
			this.title = title;
		}
	}

//	static class MonitorDialog extends JDialog implements TrackerMonitor {
//
//		JProgressBar monitor;
//		Timer timer;
//		int frameCount = Integer.MIN_VALUE;
//
//		MonitorDialog(TFrame frame, String path) {
//			super(frame, false);
//			setName(path);
//			JPanel contentPane = new JPanel(new BorderLayout());
//			setContentPane(contentPane);
//			monitor = new JProgressBar(0, 100);
//			monitor.setValue(0);
//			monitor.setStringPainted(true);
//			// make timer to step progress forward slowly
//			timer = new Timer(300, new ActionListener() {
//				@Override
//				public void actionPerformed(ActionEvent e) {
//					if (!isVisible())
//						return;
//					int progress = monitor.getValue() + 1;
//					if (progress <= 20)
//						monitor.setValue(progress);
//				}
//			});
//			timer.setRepeats(true);
//			this.addWindowListener(new WindowAdapter() {
//				@Override
//				public void windowClosing(WindowEvent e) {
//					VideoIO.setCanceled(true);
//				}
//			});
////	  	// give user a way to close unwanted dialog: double-click
////	  	addMouseListener(new MouseAdapter() {
////	  		public void mouseClicked(MouseEvent e) {
////	  			if (e.getClickCount()==2) {
////	        	close();
////	  			}
////	  		}
////	  	});
//			JPanel progressPanel = new JPanel(new BorderLayout());
//			progressPanel.setBorder(BorderFactory.createEmptyBorder(4, 30, 8, 30));
//			progressPanel.add(monitor, BorderLayout.CENTER);
//			progressPanel.setOpaque(false);
//			JLabel label = new JLabel(TrackerRes.getString("Tracker.Splash.Loading") //$NON-NLS-1$
//					+ " \"" + XML.getName(path) + "\""); //$NON-NLS-1$ //$NON-NLS-2$
//			JPanel labelbar = new JPanel();
//			labelbar.add(label);
//			JButton cancelButton = new JButton(TrackerRes.getString("Dialog.Button.Cancel")); //$NON-NLS-1$
//			cancelButton.addActionListener(new ActionListener() {
//				@Override
//				public void actionPerformed(ActionEvent e) {
//					VideoIO.setCanceled(true);
//					close();
//				}
//			});
//			JPanel buttonbar = new JPanel();
//			buttonbar.add(cancelButton);
//			contentPane.add(labelbar, BorderLayout.NORTH);
//			contentPane.add(progressPanel, BorderLayout.CENTER);
//			contentPane.add(buttonbar, BorderLayout.SOUTH);
//			FontSizer.setFonts(contentPane, FontSizer.getLevel());
//			pack();
//			Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
//			int x = (dim.width - getBounds().width) / 2;
//			int y = (dim.height - getBounds().height) / 2;
//			setLocation(x, y);
//			timer.start();
//		}
//
//		@Override
//		public void stop() {
//			timer.stop();
//		}
//
//		@Override
//		public void restart() {
//			monitor.setValue(0);
//			frameCount = Integer.MIN_VALUE;
//			// restart timer
//			timer.start();
//		}
//
//		@Override
//		public void setProgressAsync(int progress) {
//			monitor.setValue(progress);
//		}
//
//		@Override
//		public void setFrameCount(int count) {
//			frameCount = count;
//		}
//
//		@Override
//		public int getFrameCount() {
//			return frameCount;
//		}
//
//		@Override
//		public void close() {
//			timer.stop();
//			setVisible(false);
//			TrackerIO.monitors.remove(this);
//			dispose();
//		}
//
//	}

	/**
	 * Transferable class for copying images to the system clipboard.
	 */
	static class TransferImage implements Transferable {
		private Image image;

		TransferImage(Image image) {
			this.image = image;
		}

		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[] { DataFlavor.imageFlavor };
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (!isDataFlavorSupported(flavor))
				throw new UnsupportedFlavorException(flavor);
			return image;
		}
	}

	static void closeMonitor(String fileName) {
		for (TrackerMonitor monitor : monitors) {
			if (fileName == null) {
				monitor.close();
			} else if (XML.forwardSlash(monitor.getName()).endsWith(XML.forwardSlash(fileName))) {
				monitor.close();
				monitors.remove(monitor);
				return;
			}
		}
		monitors.clear();
	}

	/**
	 * Returns the metadata, if any, defined in HTML code
	 * 
	 * @param htmlCode the HTML code
	 * @return a Map containing metadata names to values found in the code
	 */
	public static ArrayList<String[]> getMetadataFromHTML(String htmlCode) {
		ArrayList<String[]> results = new ArrayList<String[]>();
		if (htmlCode == null)
			return results;
		String[] parts = htmlCode.split("<meta name=\""); //$NON-NLS-1$
		for (int i = 1; i < parts.length; i++) { // ignore parts[0]
			// parse metadata and add to array
			int n = parts[i].indexOf("\">"); //$NON-NLS-1$
			if (n > -1) {
				parts[i] = parts[i].substring(0, n);
				String divider = "\" content=\""; //$NON-NLS-1$
 				String[] subparts = parts[i].split(divider);
				if (subparts.length > 1) {
					String name = subparts[0];
					String value = subparts[1];
					results.add(new String[] { name, value });
				}
			}
		}
		return results;
	}

	static void setProgress(String name, String string, int framesLoaded) {
		for (TrackerMonitor monitor : TrackerIO.monitors) {
			String monitorName = XML.forwardSlash(monitor.getName());
			if (monitorName.endsWith(name)) {
				int progress;
				if (monitor.getFrameCount() != Integer.MIN_VALUE) {
					progress = 20 + (int) (framesLoaded * 60.0 / monitor.getFrameCount());
				} else {
					progress = 20 + ((framesLoaded / 20) % 60);
				}
				monitor.setProgressAsync(progress);
				monitor.setTitle(
						TrackerRes.getString("TFrame.ProgressDialog.Title.FramesLoaded") + ": " + framesLoaded); //$NON-NLS-1$ //$NON-NLS-2$
				break;
			}
		}
	}
}
