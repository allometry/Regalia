import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.ImageObserver;
import java.io.IOException;
import java.net.URL;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.rsbot.event.listeners.PaintListener;
import org.rsbot.script.GEItemInfo;
import org.rsbot.script.Script;
import org.rsbot.script.ScriptManifest;
import org.rsbot.script.Skills;
import org.rsbot.script.wrappers.RSArea;
import org.rsbot.script.wrappers.RSObject;
import org.rsbot.script.wrappers.RSTile;

@ScriptManifest(authors = { "Allometry" }, category = "Crafting", name = "Regalia", version = 0.1,
		description = "" +
				"<html>" +
				"<head>" +
				"<style type=\"text/css\">" +
				"body {background: #000 url(http://scripts.allometry.com/app/webroot/img/gui/window.jpg);" +
				"font-family: Georgia, 'Times New Roman', Times, serif;" +
				"font-size: 12px;font-weight: normal;" +
				"padding: 50px 10px 45px 10px;}" +
				"</style>" +
				"</head>" +
				"<body>" +
				"<p>Regalia by Allometry</p>" +
				"<p>Makes gold rings!</p>" +
				"</body>" +
				"</html>")
public class Regalia extends Script implements PaintListener {
	private boolean isCameraRotating = false, isScriptLoaded = false, isThreadsRunning = true, isVerbose = false;
	
	private int boothID = 26972, goldBarID = 2357, goldRingID = 1635, ringMouldID = 1592;
	private int craftFurnaceParentInterfaceID = 446, craftFurnaceChildInterfaceID = 82;
	private int levelGainedParentInterfaceID = 740, levelGainedChildInterfaceID = 3;
	private int currentCraftingEP = 0, currentCraftingLevel = 0, lastEPTick = 0, startingCraftingEP = 0, startingCraftingLevel = 0;
	private int ringsMadeWidgetIndex, grossProdcutWidgetIndex, currentRuntimeWidgetIndex, craftingEPEarnedWidgetIndex, ringsToGoWidgetIndex;
	private int accumulatedRings = 0, goldRingMarketPrice = 0;
	
	private long startingTime = 0;
	
	private Image coinsImage, cursorImage, ringImage, ringGoImage, sumImage, timeImage;
	private ImageObserver observer;
	
	private Monitor monitor = new Monitor();
	
	private NumberFormat numberFormatter = NumberFormat.getNumberInstance(Locale.US);
	
	private RSArea bankArea = new RSArea(new RSTile(3095, 3496), new RSTile(3098,3498));
	private RSArea furnaceArea = new RSArea(new RSTile(3108, 3500), new RSTile(3110, 3502));
	private RSTile furnaceTile = new RSTile(3110, 3502);
	
	private Scoreboard topLeftScoreboard, topRightScoreboard;
	
	private ScoreboardWidget ringsMade, grossProduct;
	private ScoreboardWidget currentRuntime, craftingEPEarned, ringsToGo;
	
	private String craftingEPEarnedWidgetText = "", ringsToGoWidgetText = "";
	
	private Thread monitorThread;
	
	@Override
	public boolean onStart(Map<String,String> args) {
		try {
			verbose("Attempting to read image resources from the web...");
			
			coinsImage = ImageIO.read(new URL("http://scripts.allometry.com/icons/coins.png"));
			cursorImage = ImageIO.read(new URL("http://scripts.allometry.com/app/webroot/img/cursors/cursor-01.png"));
			ringImage = ImageIO.read(new URL("http://scripts.allometry.com/icons/ring.png"));
			ringGoImage = ImageIO.read(new URL("http://scripts.allometry.com/icons/ring_go.png"));
			sumImage = ImageIO.read(new URL("http://scripts.allometry.com/icons/sum.png"));
			timeImage = ImageIO.read(new URL("http://scripts.allometry.com/icons/time.png"));
			
			verbose("Success! All image resources have been loaded...");
		} catch (IOException e) {
			log.warning("There was an issue trying to read the image resources from the web...");
		}
		
		try {
			verbose("Attempting to get the latest market prices...");
			
			GEItemInfo goldRingItem = grandExchange.loadItemInfo(goldRingID);
			
			goldRingMarketPrice = goldRingItem.getMarketPrice();
			
			verbose("Success! The gold ring is " + goldRingMarketPrice + "gp");
		} catch (Exception e) {
			log.warning("There was an issue trying to read the market prices from the web...");
		}
		
		try {
			//Assemble Top Left Widgets
			ringsMade = new ScoreboardWidget(ringImage, "");
			grossProduct = new ScoreboardWidget(coinsImage, "");
			
			//Assemble Top Right Widgets 
			currentRuntime = new ScoreboardWidget(timeImage, "");
			craftingEPEarned = new ScoreboardWidget(sumImage, "");
			ringsToGo = new ScoreboardWidget(ringGoImage, "");
			
			//Assemble Top Left Scoreboard
			topLeftScoreboard = new Scoreboard(Scoreboard.TOP_LEFT, 128, 5);
			topLeftScoreboard.addWidget(ringsMade);
			ringsMadeWidgetIndex = 0;
			
			topLeftScoreboard.addWidget(grossProduct);
			grossProdcutWidgetIndex = 1;
			
			//Assemble Top Right Scoreboard
			topRightScoreboard = new Scoreboard(Scoreboard.TOP_RIGHT, 128, 5);
			topRightScoreboard.addWidget(currentRuntime);
			currentRuntimeWidgetIndex = 0;
			
			topRightScoreboard.addWidget(craftingEPEarned);
			craftingEPEarnedWidgetIndex = 1;
			
			topRightScoreboard.addWidget(ringsToGo);
			ringsToGoWidgetIndex = 2;
			
		} catch (Exception e) {
			log.warning("There was an issue creating the scoreboard...");
		}
		
		startingCraftingEP = skills.getCurrentSkillExp(Skills.getStatIndex("Crafting"));
		startingCraftingLevel = skills.getCurrSkillLevel(Skills.getStatIndex("Crafting"));
		startingTime = System.currentTimeMillis();
		
		monitorThread = new Thread(monitor);
		monitorThread.start();
		
		isScriptLoaded = true;
		setCameraAltitude(true);
		
		return true;
	}
	
	@Override
	public int loop() {
		if(isPaused || isCameraRotating || !isLoggedIn() || isWelcomeScreen() || isLoginScreen()) return 1;
		
		if(inventoryContains(ringMouldID, goldBarID)) {
			if(!tileOnScreen(furnaceTile)) {
				if(!getMyPlayer().isMoving()) {
					walkTileMM(furnaceArea.getRandomTile());
					return random(700, 1000);
				}
			} else {
				if(!getInterface(craftFurnaceParentInterfaceID, craftFurnaceChildInterfaceID).isValid()) {
					RSObject furnace = getObjectAt(furnaceTile);
					do {
						useItem(getInventoryItemByID(goldBarID), furnace);
						
						if(!getInterface(craftFurnaceParentInterfaceID, craftFurnaceChildInterfaceID).isValid())
							wait(random(1500, 2000));
					} while(!getInterface(craftFurnaceParentInterfaceID, craftFurnaceChildInterfaceID).isValid());
				} else {
					if(atInterface(craftFurnaceParentInterfaceID, craftFurnaceChildInterfaceID, "Make All")) {
						while(inventoryContains(goldBarID)) {
							if(getInterface(levelGainedParentInterfaceID, levelGainedChildInterfaceID).isValid())
								atInterface(levelGainedParentInterfaceID, levelGainedChildInterfaceID, "");
							
							if(inventoryContains(goldBarID))
								wait(100);
						}
					}
				}
			}
		} else if(inventoryContains(ringMouldID, goldRingID) && !inventoryContains(goldBarID)) {
			if(!bankArea.contains(getMyPlayer().getLocation()) || getNearestObjectByID(boothID) == null) {
				if(!getMyPlayer().isMoving()) {
					walkTileMM(bankArea.getRandomTile());
					return random(700, 1000);
				}
			} else {
				if(!bank.isOpen()) {
					if(getNearestObjectByID(boothID) != null) {
						if(!bank.isOpen()) {
							atObject(getNearestObjectByID(boothID), "Use-quickly");
							return random(1500, 2000);
						}							
					}
				}
				
				if(bank.depositAllExcept(ringMouldID))
					return 1;
			}
		} else if(inventoryEmptyExcept(ringMouldID)) {
			if(!bank.isOpen()) {
				if(getNearestObjectByID(boothID) != null) {
					if(!bank.isOpen()) {
						atObject(getNearestObjectByID(boothID), "Use-quickly");
						return random(1500, 2000);
					}							
				}
			}
			
			if(bank.withdraw(goldBarID, 0))
				return 1;
		}
		
		return 1;
	}
	
	@Override
	public void onRepaint(Graphics g2) {
		if(isPaused || !isLoggedIn()) return ;
		
		Graphics2D g = (Graphics2D)g2;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		
		if(!isScriptLoaded) {
			Scoreboard loadingBoard = new Scoreboard(Scoreboard.BOTTOM_RIGHT, 128, 5);
			loadingBoard.addWidget(new ScoreboardWidget(timeImage, "Loading..."));
			loadingBoard.drawScoreboard(g);
			
			return ;
		}
		
		//Draw Custom Mouse Cursor
		g.drawImage(cursorImage, getMouseLocation().x - 16, getMouseLocation().y - 16, observer);
		
		//Draw Top Left Scoreboard
		topLeftScoreboard.getWidget(ringsMadeWidgetIndex).setWidgetText(numberFormatter.format(accumulatedRings));
		topLeftScoreboard.getWidget(grossProdcutWidgetIndex).setWidgetText("$" + numberFormatter.format(accumulatedRings * goldRingMarketPrice));
		topLeftScoreboard.drawScoreboard(g);
		
		//Draw Top Right Scoreboard
		topRightScoreboard.getWidget(currentRuntimeWidgetIndex).setWidgetText(millisToClock(System.currentTimeMillis() - startingTime));
		topRightScoreboard.getWidget(craftingEPEarnedWidgetIndex).setWidgetText(craftingEPEarnedWidgetText);
		topRightScoreboard.getWidget(ringsToGoWidgetIndex).setWidgetText(ringsToGoWidgetText);
		topRightScoreboard.drawScoreboard(g);
	}
	
	@Override
	public void onFinish() {
		verbose("Stopping threads...");
		
		//Gracefully stop threads
		while(monitorThread.isAlive()) {
			isThreadsRunning = false;
		}
		
		verbose("Threads stopped...");
		
		//Gracefully release threads and runnable objects
		monitorThread = null;
		monitor = null;
				
		return ;
	}
	
	/**
	 * Verbose method is a log wrapper that successfully executes if the ifVerbose variable is true.
	 * 
	 * @since 0.1
	 */
	private void verbose(String message) {
		if(isVerbose) log.info(message);
	}
	
	/**
	 * Formats millisecond time into HH:MM:SS
	 * 
	 * @param milliseconds				milliseconds that should be converted into
	 * 									the HH:MM:SS format
	 * 									@see java.lang.System
	 * @return							formatted HH:MM:SS string
	 * @since 0.1
	 */
	private String millisToClock(long milliseconds) {
		long seconds = (milliseconds / 1000), minutes = 0, hours = 0;
		
		if (seconds >= 60) {
			minutes = (seconds / 60);
			seconds -= (minutes * 60);
		}
		
		if (minutes >= 60) {
			hours = (minutes / 60);
			minutes -= (hours * 60);
		}
		
		return (hours < 10 ? "0" + hours + ":" : hours + ":")
				+ (minutes < 10 ? "0" + minutes + ":" : minutes + ":")
				+ (seconds < 10 ? "0" + seconds : seconds);
	}
	
	/**
	 * Monitor class assembles and updates all experience points and levels gained. The
	 * class also maintains strings for the onRepaint method.
	 * 
	 * @author allometry
	 * @version 1.1
	 * @since 1.0
	 */
	public class Monitor implements Runnable {
		@Override
		public void run() {
			while(isThreadsRunning) {
				while(isLoggedIn() && !isPaused && isThreadsRunning) {
					currentCraftingEP = skills.getCurrentSkillExp(Skills.getStatIndex("Crafting"));
					currentCraftingLevel = skills.getCurrSkillLevel(Skills.getStatIndex("Crafting"));
					
					accumulatedRings = (currentCraftingEP - startingCraftingEP) / 15;
						
					if(currentCraftingLevel > startingCraftingLevel)
						craftingEPEarnedWidgetText = numberFormatter.format((currentCraftingEP - startingCraftingEP)) + " (+" + (currentCraftingLevel - startingCraftingLevel) + ")";
					else
						craftingEPEarnedWidgetText = numberFormatter.format((currentCraftingEP - startingCraftingEP));
					
					ringsToGoWidgetText = numberFormatter.format(Math.ceil(skills.getXPToNextLevel(Skills.getStatIndex("Crafting")) / 15));
				}
			}
		}
	}
	
	/**
	 * Scoreboard is a class for assembling individual scoreboards with widgets
	 * in a canvas space.
	 * 
	 * @author allometry
	 * @version 1.0
	 * @since 1.0
	 */
	public class Scoreboard {
		public static final int TOP_LEFT = 1, TOP_RIGHT = 2, BOTTOM_LEFT = 3, BOTTOM_RIGHT = 4;
		public static final int gameCanvasTop = 25, gameCanvasLeft = 25, gameCanvasBottom = 309, gameCanvasRight = 487;

		private ImageObserver observer = null;

		private int scoreboardLocation, scoreboardX, scoreboardY, scoreboardWidth,
				scoreboardHeight, scoreboardArc;

		private ArrayList<ScoreboardWidget> widgets = new ArrayList<ScoreboardWidget>();
		
		/**
		 * Creates a new instance of Scoreboard.
		 * 
		 * @param scoreboardLocation	the location of where the scoreboard should be drawn on the screen
		 * 								@see Scoreboard.TOP_LEFT
		 * 								@see Scoreboard.TOP_RIGHT
		 * 								@see Scoreboard.BOTTOM_LEFT
		 * 								@see Scoreboard.BOTTOM_RIGHT
		 * @param width					the pixel width of the scoreboard
		 * @param arc					the pixel arc of the scoreboard rounded rectangle
		 * @since 1.0
		 */
		public Scoreboard(int scoreboardLocation, int width, int arc) {
			this.scoreboardLocation = scoreboardLocation;
			scoreboardHeight = 10;
			scoreboardWidth = width;
			scoreboardArc = arc;

			switch (scoreboardLocation) {
			case 1:
				scoreboardX = gameCanvasLeft;
				scoreboardY = gameCanvasTop;
				break;

			case 2:
				scoreboardX = gameCanvasRight - scoreboardWidth;
				scoreboardY = gameCanvasTop;
				break;

			case 3:
				scoreboardX = gameCanvasLeft;
				break;

			case 4:
				scoreboardX = gameCanvasRight - scoreboardWidth;
				break;
			}
		}
		
		/**
		 * Adds a ScoreboardWidget to the Scoreboard.
		 * 
		 * @param widget				an instance of a ScoreboardWidget containing an image
		 * 								and text
		 * 								@see ScoreboardWidget
		 * @return						true if the widget was added to Scoreboard
		 * @since 1.0
		 */
		public boolean addWidget(ScoreboardWidget widget) {
			return widgets.add(widget);
		}
		
		/**
		 * Gets a ScoreboardWidget by it's index within Scoreboard.
		 * 
		 * @param widgetIndex			the index of the ScoreboardWidget
		 * @return						an instance of ScoreboardWidget
		 * @since 1.0
		 */
		public ScoreboardWidget getWidget(int widgetIndex) {
			try {
				return widgets.get(widgetIndex);
			} catch (Exception e) {
				log.warning("Warning: " + e.getMessage());
				return null;
			}
		}
		
		/**
		 * Gets the Scoreboard widgets.
		 * 
		 * @return						an ArrayList filled with ScoreboardWidget's
		 */
		public ArrayList<ScoreboardWidget> getWidgets() {
			return widgets;
		}
		
		/**
		 * Draws the Scoreboard and ScoreboardWidget's to an instances of Graphics2D.
		 * 
		 * @param g						an instance of Graphics2D
		 * @return						true if Scoreboard was able to draw to the Graphics2D instance and false if it wasn't
		 * @since 1.0
		 */
		public boolean drawScoreboard(Graphics2D g) {
			try {
				if(scoreboardHeight <= 10) {
					for (ScoreboardWidget widget : widgets) {
						scoreboardHeight += widget.getWidgetImage().getHeight(observer) + 4;
					}
				}

				if (scoreboardLocation == 3 || scoreboardLocation == 4) {
					scoreboardY = gameCanvasBottom - scoreboardHeight;
				}

				RoundRectangle2D scoreboard = new RoundRectangle2D.Float(
						scoreboardX, scoreboardY, scoreboardWidth,
						scoreboardHeight, scoreboardArc, scoreboardArc);

				g.setColor(new Color(0, 0, 0, 127));
				g.fill(scoreboard);

				int x = scoreboardX + 5;
				int y = scoreboardY + 5;
				for (ScoreboardWidget widget : widgets) {
					widget.drawWidget(g, x, y);
					y += widget.getWidgetImage().getHeight(observer) + 4;
				}

				return true;
			} catch (Exception e) {
				return false;
			}
		}
		
		/**
		 * Returns the height of the Scoreboard with respect to it's contained ScoreboardWidget's.
		 * 
		 * @return						the pixel height of the Scoreboard
		 * @since 1.0 
		 */
		public int getHeight() {
			return scoreboardHeight;
		}
	}
	
	/**
	 * ScoreboardWidget is a container intended for use with a Scoreboard. Scoreboards contain
	 * an image and text, which are later drawn to an instance of Graphics2D.
	 * 
	 * @author allometry
	 * @version 1.0
	 * @since 1.0
	 * @see Scoreboard
	 */
	public class ScoreboardWidget {
		private ImageObserver observer = null;
		private Image widgetImage;
		private String widgetText;
		
		/**
		 * Creates a new instance of ScoreboardWidget.
		 * 
		 * @param widgetImage			an instance of an Image. Recommended size is 16x16 pixels
		 * 								@see java.awt.Image
		 * @param widgetText			text to be shown on the right of the widgetImage
		 * @since 1.0
		 */
		public ScoreboardWidget(Image widgetImage, String widgetText) {
			this.widgetImage = widgetImage;
			this.widgetText = widgetText;
		}
		
		/**
		 * Gets the widget image.
		 * 
		 * @return						the Image of ScoreboardWidget
		 * 								@see java.awt.Image
		 * @since 1.0
		 */
		public Image getWidgetImage() {
			return widgetImage;
		}
		
		/**
		 * Sets the widget image.
		 * 
		 * @param widgetImage			an instance of an Image. Recommended size is 16x16 pixels
		 * 								@see java.awt.Image
		 * @since 1.0
		 */
		public void setWidgetImage(Image widgetImage) {
			this.widgetImage = widgetImage;
		}
		
		/**
		 * Gets the widget text.
		 * 
		 * @return						the text of ScoreboardWidget
		 * @since 1.0
		 */
		public String getWidgetText() {
			return widgetText;
		}
		
		/**
		 * Sets the widget text.
		 * 
		 * @param widgetText			text to be shown on the right of the widgetImage
		 * @since 1.0
		 */
		public void setWidgetText(String widgetText) {
			this.widgetText = widgetText;
		}
		
		/**
		 * Draws the ScoreboardWidget to an instance of Graphics2D.
		 * 
		 * @param g						an instance of Graphics2D
		 * @param x						horizontal pixel location of where to draw the widget 
		 * @param y						vertical pixel location of where to draw the widget
		 * @since 1.0
		 */
		public void drawWidget(Graphics2D g, int x, int y) {
			g.setColor(Color.white);
			g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

			g.drawImage(widgetImage, x, y, observer);
			g.drawString(widgetText, x + widgetImage.getWidth(observer) + 4, y + 12);
		}
	}
}