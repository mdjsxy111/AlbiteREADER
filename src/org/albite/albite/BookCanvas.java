/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.albite.albite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.Timer;
import java.util.Vector;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;
import org.albite.book.model.Book;
import org.albite.book.model.BookException;
import org.albite.book.model.Chapter;
import org.albite.book.view.Booklet;
import org.albite.font.AlbiteFont;
import org.albite.book.view.PageDummy;
import org.albite.book.view.PageText;
import org.albite.font.AlbiteFontException;
import org.geometerplus.zlibrary.text.hyphenation.ZLTextTeXHyphenator;

/**
 *
 * @author Albus Dumbledore
 */
public class BookCanvas extends Canvas {
    private static final int TASK_NONE           = 0;
    private static final int TASK_MENU           = 1;
    private static final int TASK_LIBRARY        = 2;
    private static final int TASK_DICTIONARY     = 3;
    private static final int TASK_FONTSIZE       = 4;
    private static final int TASK_COLORPROFILE   = 5;
    
    public  static final int MENU_HEIGHT            = 45;
    public  static final int MARGIN_WIDTH           = 10;
    private static final int STATUS_BAR_SPACING     = 3;

    private static final int DRAG_TRESHOLD          = 40;
    private static final int MARGIN_CLICK_TRESHOLD  = 60;  //it'd better be enough
    private static final long HOLD_TIME             = 800; //in millis

    private long startHoldingTime;

    private static final int FPS                    = 30;
    private static final int FRAME_TIME             = 1000 / FPS;

    private static final int AUTOSAVE_TIME          = 5 * 60 * 1000;

    private static final int MODE_DONT_RENDER       = 0;
    private static final int MODE_PAGE_LOCKED       = 1;
    private static final int MODE_PAGE_LOADING      = 2;
    private static final int MODE_PAGE_READING      = 3;
    private static final int MODE_PAGE_SCROLLING    = 4;
    private static final int MODE_BUTTON_PRESSING   = 5;
    private static final int MODE_WORD_SELECTED     = 6;
    private static final int MODE_MARKING_WORDS     = 7;

    private int mode = MODE_DONT_RENDER;

    /*
     * 180-degree rotation will not be supported as it introduces code
     * complexity, that is not quite necessary
     */
    public static final int ORIENTATION_0           = Sprite.TRANS_NONE;
    public static final int ORIENTATION_90          = Sprite.TRANS_ROT90;
    public static final int ORIENTATION_180         = Sprite.TRANS_NONE;
    public static final int ORIENTATION_270         = Sprite.TRANS_ROT270;

    private int orientation = ORIENTATION_0;

    private volatile boolean     repaintButtons              = true;
    private volatile boolean     repaintStatusBar            = true;
    private volatile boolean     repaintClock                = true;
    private volatile boolean     repaintProgressBar          = false;
    
    private final char[] chapterNoChars  = {'#', '0', '0', '0'};
    private       int    pagesCount;
    private final char[] clockChars = {'0', '0', ':', '0', '0'};

    private              int statusBarHeight;
    private              int chapterNoWidth;
    private              int progressBarWidth;
    private              int progressBarHeight;
    private              int progressBarX;
    private              int clockWidth;
    
    private ImageButton[]      buttons;
    private ImageButton        waitCursor;

    //input events
    private int xx = 0;
    private int yy = 0;
    private int xxPressed = 0;
    private int yyPressed = 0;
    private ImageButton buttonPressed = null;
    
    private ColorProfile        currentProfile;

    private AlbiteFont          fontPlain;
    private AlbiteFont          fontItalic;

    private static final byte   FONT_SIZE_12        = 0;
    private static final byte   FONT_SIZE_14        = 1;
    private static final byte   FONT_SIZE_16        = 2;
    private static final byte   FONT_SIZE_18        = 3;

    private static final byte[] FONT_SIZES = {12, 14, 16, 18};

    private boolean             fontGrowing = true;
    private byte                currentFontSizeIndex = FONT_SIZE_16;

    private AlbiteFont fontStatus;

    private Book currentBook;

    private ZLTextTeXHyphenator hyphenator = new ZLTextTeXHyphenator();;
    private Vector dictionaries = new Vector(10);

    private Booklet chapterBooklet;
    private PageCanvas prevPageCanvas;
    private PageCanvas currentPageCanvas;
    private PageCanvas nextPageCanvas;
    private int currentPageCanvasX;

    private Timer timer;
    private ScrollingTimerTask scrollingTimerTask;
    private SavingTimerTask savingTimerTask;

    private AlbiteMIDlet app;

    private RecordStore rs;

    private boolean initialized = false;

    public BookCanvas(AlbiteMIDlet app) { //as its public it is not REALLY a singleton, but its certain that this one would not be called twice
        this.app = app;
        openRMSAndLoadData();
    }

    public synchronized final void initialize() {

        //prevent re-initialization
        if(initialized) {
            return;
        }

        loadFont();
        loadStatusFont();
        
        statusBarHeight = fontStatus.lineHeight + (STATUS_BAR_SPACING * 2);

        /* Clock: 00:00 = 5 chars */
        clockWidth = (fontStatus.maximumWidth * clockChars.length) + (STATUS_BAR_SPACING * 2);

        /*
         * We assume that there would be no more than 999 chapters
         */
        chapterNoWidth = (fontStatus.maximumWidth * chapterNoChars.length) + (STATUS_BAR_SPACING * 2);

        progressBarWidth = getWidth() - (STATUS_BAR_SPACING * 4);
        if (chapterNoWidth > clockWidth) {
            progressBarWidth -= chapterNoWidth * 2;
        } else {
            progressBarWidth -= clockWidth * 2;
        }

        progressBarX = (getWidth() - progressBarWidth) / 2;

        progressBarHeight = (statusBarHeight - (STATUS_BAR_SPACING * 2)) / 2;

        //Load menu images
        //Images cannot be stored normally (i.e. as Image objects) as they need
        //to be mutable: one should be able to select the color of the image
        //without affecting the alpha channel
        buttons    = new ImageButton[5];
        buttons[0] = new ImageButton("/res/gfx/button_menu.ali", TASK_MENU);
        buttons[1] = new ImageButton("/res/gfx/button_library.ali", TASK_LIBRARY);
        buttons[2] = new ImageButton("/res/gfx/button_dict.ali", TASK_DICTIONARY);
        buttons[3] = new ImageButton("/res/gfx/button_font_size.ali", TASK_FONTSIZE);
        buttons[4] = new ImageButton("/res/gfx/button_color_profile.ali", TASK_COLORPROFILE);

        if (buttons.length > 0) {
            int x = 0;
            for (int i=0; i<buttons.length; i++) {
                buttons[i].setX(x);
                buttons[i].setY(0);
                x += buttons[i].getWidth();
            }
        }

        waitCursor = new ImageButton("/res/gfx/hourglass.ali", TASK_NONE);

        ColorProfile day = ColorProfile.DEFAULT_DAY;
        ColorProfile night = ColorProfile.DEFAULT_NIGHT;

        //shall not forget these three lines
        day.link(night);
        currentProfile = day;
        applyColorProfile();

        initializePageCanvases();

        System.gc();
        
        timer = new Timer();
        timer.schedule(new ClockTimerTask(this), 60000, 60000);

        initialized = true;
    }

    private void initializePageCanvases() {
        currentPageCanvas   = new PageCanvas(this);
        nextPageCanvas      = new PageCanvas(this);
        prevPageCanvas      = new PageCanvas(this);

        currentPageCanvasX = 0;
    }

    public void paint(Graphics g) {
        if (mode != MODE_DONT_RENDER) {
            final int w = getWidth();
            final int h = getHeight();

            if (repaintButtons) {
                drawButtons(g);
            }

            if (repaintStatusBar) {
                repaintStatusBar = false;

                g.setColor(currentProfile.getColor(ColorProfile.CANVAS_BACKGROUND_COLOR));
                g.fillRect(0, h - statusBarHeight, w, statusBarHeight);

                drawProgressBar(g);
                drawClock(g);
            } else {
                /* If not the whole status bar is to be updated, check if parts
                 * of it are
                 */
                
                if (repaintProgressBar) {
                    drawProgressBar(g);
                }

                if (repaintClock) {
                    drawClock(g);
                }
            }

            if (repaintProgressBar) {
                drawProgressBar(g);
            }

            switch (mode) {

                //this way one may implement layers
                case MODE_PAGE_READING:
                case MODE_PAGE_SCROLLING:
                case MODE_PAGE_LOCKED:

                    final int anchor = Graphics.TOP | Graphics.LEFT;
                    final Image imageC = currentPageCanvas.getImage();;
                    final Image imageP = prevPageCanvas.getImage();
                    final Image imageN = nextPageCanvas.getImage();
                    final int imageWidth = imageC.getWidth();
                    final int imageHeight = imageC.getHeight();
                    int x = 0;
                    int y = 0;

                    switch(orientation) {
                        case ORIENTATION_0:
                            g.setClip(0, MENU_HEIGHT, w, imageHeight);
                            x = MARGIN_WIDTH + currentPageCanvasX;
                            y = MENU_HEIGHT;
                            break;

                        case ORIENTATION_90:
                        case ORIENTATION_270:
                            g.setClip(0, 0, w, h);
                            x = MARGIN_WIDTH + currentPageCanvasX;
                            y = MARGIN_WIDTH;
                            break;
                    }
                    
                    g.setColor(currentProfile.getColor(ColorProfile.CANVAS_BACKGROUND_COLOR));
                    g.fillRect(0, 0, w, h);
                    g.drawRegion(imageP, 0, 0, imageWidth, imageHeight, orientation, x - w, y, anchor);
                    g.drawRegion(imageC, 0, 0, imageWidth, imageHeight, orientation, x    , y, anchor);
                    g.drawRegion(imageN, 0, 0, imageWidth, imageHeight, orientation, x + w, y, anchor);
                    break;

                case MODE_PAGE_LOADING:
                    //draw loading cursor on top
                    waitCursor.draw(g, (w - waitCursor.getWidth())/2, (h - waitCursor.getHeight())/2);
            }
        }
    }
    
    private void drawButtons(Graphics g) {
        g.setColor(currentProfile.getColor(ColorProfile.CANVAS_BACKGROUND_COLOR));
        g.fillRect(0, 0, getWidth(), MENU_HEIGHT);

        if (buttons.length > 0) {
            for (int i=0; i<buttons.length; i++)
                buttons[i].draw(g, buttons[i].getX(), buttons[i].getY());
            repaintButtons = false;
        }
    }

    private void drawProgressBar(Graphics g) {
        repaintProgressBar = false;

        final int w = getWidth();
        final int h = getHeight();

        g.setColor(currentProfile.getColor(ColorProfile.CANVAS_BACKGROUND_COLOR));
        g.fillRect(0, h - statusBarHeight, w - clockWidth, statusBarHeight);

        /* drawing current chapter area */
        final char[] chapterNoChars_ = chapterNoChars;
        final int currentChapterNo = currentBook.getCurrentChapter().getChapterNo();

        int i = 1;
        if (currentChapterNo > 99) {
            chapterNoChars_[i] = (char)('0' + (currentChapterNo / 100));
            i ++;
        }
        if (currentChapterNo > 9) {
            chapterNoChars_[i] = (char)('0' + ((currentChapterNo % 100) / 10));
            i ++;
        }

        chapterNoChars_[i] = (char)('0' + ((currentChapterNo % 100) % 10));
        i++;

        fontStatus.drawChars(g, currentProfile.getColor(ColorProfile.STATUS_BAR_TEXT_COLOR), chapterNoChars_, STATUS_BAR_SPACING, h - statusBarHeight + STATUS_BAR_SPACING, 0, i);

        /* drawing progress bar */
        g.setColor(currentProfile.getColor(ColorProfile.STATUS_BAR_TEXT_COLOR));

        g.drawRect(progressBarX, h - ((statusBarHeight + progressBarHeight) / 2), progressBarWidth, progressBarHeight);

        final int barFilledWidth;
        if (pagesCount > 0) {
            barFilledWidth = (int)(progressBarWidth * (((float)chapterBooklet.getCurrentPageIndex() - 1) / pagesCount));
        } else {
            barFilledWidth = progressBarWidth;
        }
        g.fillRect(progressBarX, h - ((statusBarHeight + progressBarHeight) / 2), barFilledWidth, progressBarHeight);
    }

    private void drawClock(Graphics g) {
        repaintClock = false;

        final int w = getWidth();
        final int h = getHeight();
        
        g.setColor(currentProfile.getColor(ColorProfile.CANVAS_BACKGROUND_COLOR));
        g.fillRect(w - clockWidth, h - statusBarHeight, clockWidth, statusBarHeight);

        final Calendar calendar = Calendar.getInstance();
        final int hour = calendar.get(Calendar.HOUR_OF_DAY);
        final int minute = calendar.get(Calendar.MINUTE);
        final char[] clock = clockChars;
        clock[0] = (char)('0' + (hour / 10));
        clock[1] = (char)('0' + (hour % 10));
        clock[3] = (char)('0' + (minute / 10));
        clock[4] = (char)('0' + (minute % 10));
        final int clockPixelWidth = fontStatus.charsWidth(clock, 0, clock.length);
        fontStatus.drawChars(g, currentProfile.getColor(ColorProfile.STATUS_BAR_TEXT_COLOR), clock, w - clockPixelWidth - STATUS_BAR_SPACING, h - statusBarHeight + STATUS_BAR_SPACING);
    }

    private ImageButton findButtonPressed(int x, int y) {
        for (int i=0; i<buttons.length; i++)
            if (buttons[i].buttonPressed(x, y))
                return buttons[i];
        return null;
    }

    public void pointerPressed(int x, int y) {
        //System.out.println("Pressed when mode: " + mode);
        xx = xxPressed = x;
        yy = yyPressed = y;
        startHoldingTime = System.currentTimeMillis();

        switch(mode) {
            case MODE_PAGE_READING:
                if (y <= MENU_HEIGHT) {
                    //has a button been pressed?
                    buttonPressed = findButtonPressed(x, y);
                    if (buttonPressed != null) {
                        mode = MODE_BUTTON_PRESSING;
                        buttonPressed.setColor(currentProfile.getColor(ColorProfile.MENU_BUTTONS_PRESSED_COLOR));
                        repaintButtons = true;
                        repaint();
                        serviceRepaints();
                    }
                }
                break;
        }
    }

    public void pointerReleased(int x, int y) {
        //System.out.println("Released when mode: " + mode);
        xx = x;
        yy = y;

        final int w = getWidth();
        final int h = getHeight();

        boolean holding = false;
        if (System.currentTimeMillis() - startHoldingTime > HOLD_TIME)
            holding = true;
        
        switch(mode) {
            case MODE_PAGE_READING:
                //then it's somewhere in the page area
                if (holding) {
                    //System.out.println("Dictionary;");
                //show menu for selected word (if a word is selected and not whitespace)
                    //TODO: requires transformation if orientation != ORIENTATION_0
                    /*
                    //System.out.println("Holding for word at " + (x-MARGIN_WIDTH) + "x" + (y-MENU_HEIGHT));
                    Region r = currentPageView.page.getRegionAt(x-MARGIN_WIDTH, y-MENU_HEIGHT);
//                    RegionText rt = (RegionText)r;
                    if (r == null)
                        //System.out.println("No region found");
                    else
                        //System.out.println("Region FOUND!");
                        //System.out.println(r.getClass().getName());
                    //System.out.println();
                     * 
                     */
                } else {
                    if (x > w-MARGIN_CLICK_TRESHOLD) {
                        //Right Page position
//                        scrollingThread.animateScrollPage(ScrollingThread.SCROLL_NEXT);
                        scheduleScrolling(ScrollingTimerTask.SCROLL_NEXT);
                        mode = MODE_PAGE_SCROLLING;
                    }

                    if (x < MARGIN_CLICK_TRESHOLD) {
                        //Right Page position
//                        scrollingThread.animateScrollPage(ScrollingThread.SCROLL_PREV);
                        mode = MODE_PAGE_SCROLLING;
                        scheduleScrolling(ScrollingTimerTask.SCROLL_PREV);
                    }
                }
                break;
                
            case MODE_PAGE_SCROLLING:
                final int cx = currentPageCanvasX;

                if (cx == 0) {
                    stopScrolling();
                    mode = MODE_PAGE_READING;
                    break;
                }
                
                if (cx < -DRAG_TRESHOLD) {
                    scheduleScrolling(ScrollingTimerTask.SCROLL_NEXT);
                    break;
                }

                if (cx > DRAG_TRESHOLD) {
                    scheduleScrolling(ScrollingTimerTask.SCROLL_PREV);
                    break;
                }

                if (cx > 0) {
                    scheduleScrolling(ScrollingTimerTask.SCROLL_SAME_PREV);
                    break;
                }

                if (cx <= 0) {
                    scheduleScrolling(ScrollingTimerTask.SCROLL_SAME_NEXT);
                    break;
                }

                mode = MODE_PAGE_READING;
                break;

            case MODE_BUTTON_PRESSING:

                //restore original color or the button
                buttonPressed.setColor(currentProfile.getColor(ColorProfile.MENU_BUTTONS_COLOR));
                repaintButtons = true;
                repaint();
                serviceRepaints();

                if (buttonPressed == findButtonPressed(x, y)) {
                    switch(buttonPressed.getTask()) {
                        case TASK_FONTSIZE:
                            cycleFontSizes();
                            break;

                        case TASK_COLORPROFILE:
                            cycleColorProfiles();
                            break;

                        case TASK_LIBRARY:
                            openLibrary();
                            break;

                        case TASK_MENU:
                            if (holding) {
                                //Exit midlet if user holds over the menu button
                                app.exitMIDlet();
                            }
                    }
                }
                buttonPressed = null;
                mode = MODE_PAGE_READING;
                break;
        }
    }

    public void pointerDragged(int x, int y) {

        switch(mode) {
            case MODE_PAGE_SCROLLING:
            case MODE_PAGE_READING:
                mode = MODE_PAGE_SCROLLING;
                stopScrolling();
                scrollPagesDx(x-xx);
                repaint();
                break;
        }

        //It's essential that these values are updated AFTER the switch statement!
        xx = x;
        yy = y;
    }

    public void keyPressed(int k) {
        processKeys(k);
    }

    public void keyRepeated(int k) {
        processKeys(k);
    }

    private void processKeys(int k) {
        int kga = getGameAction(k);

        //System.out.println("Keypress when mode: " + mode);
        switch(mode) {
            case MODE_PAGE_READING:
                switch(kga) {
                    case LEFT:
                        scheduleScrolling(ScrollingTimerTask.SCROLL_PREV);
                        return;

                    case RIGHT:
                        scheduleScrolling(ScrollingTimerTask.SCROLL_NEXT);
                        return;

                    case FIRE:
                        //menu
                        return;

                    case GAME_A:
                        //open library
                        openLibrary();
                        return;

                    case GAME_B:
                        //open dictionary and unit converter
                        return;

                    case GAME_C:
                        //change font size
                        cycleFontSizes();
                        return;

                    case GAME_D:
                        //change color profile
                        cycleColorProfiles();
                        return;
                }
        }
    }

    public synchronized final void openBook(String bookURL) throws IOException, BookException {

        //If the book is already open, no need to load it again
        if (isBookOpen() && currentBook.getArchive().getFileURL().equals(bookURL)) {
            mode = MODE_PAGE_READING;
            return;
        }

        //try to open the book
        Book newBook = new Book();

        newBook.open(bookURL);

        //Try freeing resources before showing book
        System.gc();

        //All was OK, let's close old book
        
        //close current book
        closeBook();

        currentBook = newBook;

        //load hyphenator and dictionaries according to book language
        hyphenator.load(currentBook.getLanguage());
//        dictionary.load(language);

        goToPosition(newBook.getCurrentChapter(), newBook.getCurrentChapterPosition());
        startAutomaticSaving();
        mode = MODE_PAGE_READING;
        //System.out.println("Book loaded");
    }

    private void closeBook() {
        if (isBookOpen()) {
            stopAutomaticSaving();
            saveBookOptions();
            currentBook.close();
            currentBook = null;
            chapterBooklet = null;
        }
    }

    public synchronized final void saveBookOptions() {
        currentBook.setCurrentChapterPos(currentPageCanvas.page.getStart());
        currentBook.saveUserData();
    }

    public synchronized final boolean isBookOpen() {
        return currentBook != null;
    }

    private synchronized void startAutomaticSaving() {
        if (savingTimerTask == null) {
            savingTimerTask = new SavingTimerTask(app, this);
            timer.schedule(savingTimerTask, AUTOSAVE_TIME, AUTOSAVE_TIME);
        } else {
            //System.out.println("Autosave scheduling skipped");
        }
    }

    private synchronized void stopAutomaticSaving() {
        if (savingTimerTask != null) {
            savingTimerTask.cancel();
            savingTimerTask = null;
        }
    }

    private synchronized void scheduleScrolling(int scrollMode) {
        if (scrollingTimerTask == null) {
            scrollingTimerTask = new ScrollingTimerTask(this, scrollMode);
            timer.scheduleAtFixedRate(scrollingTimerTask, FRAME_TIME, FRAME_TIME);
        } else {
            //System.out.println("Scrolling scheduling skipped");
        }
    }

    private synchronized void stopScrolling() {
        if (scrollingTimerTask != null) {
            scrollingTimerTask.cancel();
            scrollingTimerTask = null;
        }
    }

    private void scrollPagesDx(int dx) {
        if (orientation == ORIENTATION_270) {
            //System.out.println("Inverted scrolling");
            dx *= -1; //invert direction
        }

        currentPageCanvasX += dx;
    }
    
    /**
     * Scrolls the three PageCanvases across the screen.
     *
     * @param dx Relative amount to scroll
     * @param fullPage If true, the tree scroll to the next/previous page.
     * If false, scrolls back to the current page
     *
     */
    protected synchronized final void scrollPages(int dx, boolean fullPage) {
        scrollPagesDx(dx);
        final int w = getWidth();

        if (fullPage) {

            if (currentPageCanvasX >= w) {
                //loading prev page
                currentPageCanvasX = w;
                mode = MODE_PAGE_LOCKED;

                if (prevPageCanvas.page instanceof PageDummy) {
                    PageDummy pd = (PageDummy)prevPageCanvas.page;
                    byte pdType = pd.getType();
                    switch (pdType) {
                        case PageDummy.TYPE_CHAPTER_PREV:
                            stopScrolling();
                            mode = MODE_PAGE_LOADING;
                            repaint();
                            serviceRepaints();
                            goToLastPage(currentBook.getCurrentChapter().getPrevChapter());
                            repaint();
                            serviceRepaints();
                            return;

                        case PageDummy.TYPE_BOOK_START:
                            stopScrolling();
                            mode = MODE_PAGE_SCROLLING;
                            scheduleScrolling(ScrollingTimerTask.SCROLL_BOOK_START);
                            repaint();
                            serviceRepaints();
                            return;
                    }
                }

                if (prevPageCanvas.page instanceof PageText) {
                    PageText pt = (PageText)prevPageCanvas.page;
                    byte ptType = pt.getType();

                    switch(ptType) {

                        case PageText.TYPE_IMAGE:
                        case PageText.TYPE_TEXT:
                            stopScrolling();
                            repaint();
                            loadPrevPage();
                            repaint();
                            serviceRepaints();
                            return;
                    }
                }
            }
        
            if (currentPageCanvasX <= -w) {
                //loading next page
                serviceRepaints();
                currentPageCanvasX = -w;
                mode = MODE_PAGE_LOCKED;

                if (nextPageCanvas.page instanceof PageDummy) {
                    PageDummy pd = (PageDummy)nextPageCanvas.page;
                    byte pdType = pd.getType();
                    switch (pdType) {
                        case PageDummy.TYPE_CHAPTER_NEXT:
                            stopScrolling();
                            mode = MODE_PAGE_LOADING;
                            repaint();
                            serviceRepaints();
                            goToFirstPage(currentBook.getCurrentChapter().getNextChapter());
                            repaint();
                            serviceRepaints();
                            return;

                        case PageDummy.TYPE_BOOK_END:
                            stopScrolling();
                            mode = MODE_PAGE_SCROLLING;
                            scheduleScrolling(ScrollingTimerTask.SCROLL_BOOK_END);
                            repaint();
                            serviceRepaints();
                            return;
                    }
                }

                if (nextPageCanvas.page instanceof PageText) {
                    PageText pt = (PageText)nextPageCanvas.page;
                    byte ptType = pt.getType();

                    switch(ptType) {

                        case PageText.TYPE_IMAGE:
                        case PageText.TYPE_TEXT:
                            stopScrolling();
                            repaint();
                            serviceRepaints(); //this removes the glitch from the page loading procedure
                            loadNextPage();
                            repaint();
                            serviceRepaints();
                            return;
                    }
                }
            }

        repaint();
        serviceRepaints();

        } else {
            
            /* scrolling to the same page */
            if ((dx < 0 && currentPageCanvasX <= 0) || (dx >= 0 && currentPageCanvasX >= 0)) {
                currentPageCanvasX = 0;
                stopScrolling();
                mode = MODE_PAGE_READING;
            }
            repaint();
            serviceRepaints();
        }
    }

    private void loadPrevPage() {
        currentPageCanvasX = 0;
        chapterBooklet.goToPrevPage();
        PageCanvas p = nextPageCanvas;
        nextPageCanvas = currentPageCanvas;
        currentPageCanvas = prevPageCanvas;
        prevPageCanvas = p;
        p.page = chapterBooklet.getPrevPage();
        p.renderPage();
        repaintProgressBar = true;
        mode = MODE_PAGE_READING;
    }
    
    private void loadNextPage() {
        currentPageCanvasX = 0;
        chapterBooklet.goToNextPage();
        PageCanvas p = prevPageCanvas;
        prevPageCanvas = currentPageCanvas;
        currentPageCanvas = nextPageCanvas;
        nextPageCanvas = p;
        p.page = chapterBooklet.getNextPage();
        p.renderPage();
        repaintProgressBar = true;
        mode = MODE_PAGE_READING;
    }

    private void loadChapter(Chapter chapter) {
        if (chapter != currentBook.getCurrentChapter() || chapterBooklet == null) { //chapter changed or book not loaded at all!
            currentBook.unloadChaptersBuffers();
            currentBook.setCurrentChapter(chapter);
            reflowPages();
            //System.out.println("Memory statistics after loading chapter `" + chapter.getTitle() + "`");
            //System.out.println("Total mem available: " + Runtime.getRuntime().totalMemory());
            //System.out.println("Free  mem available: " + Runtime.getRuntime().freeMemory());
        }
    }

    public synchronized final void goToFirstPage(Chapter chapter) {
        loadChapter(chapter);
        chapterBooklet.goToFirstPage();
        renderPages();
    }

    public synchronized final void goToLastPage(Chapter chapter) {
        loadChapter(chapter);
        chapterBooklet.goToLastPage();
        renderPages();
    }

    public synchronized final void goToPosition(Chapter chapter, int position) {
        loadChapter(chapter);
        chapterBooklet.goToPosition(position);
        renderPages();
    }

    private void renderPages() {
        prevPageCanvas.page = chapterBooklet.getPrevPage();
        currentPageCanvas.page = chapterBooklet.getCurrentPage();
        nextPageCanvas.page = chapterBooklet.getNextPage();

        prevPageCanvas.renderPage();
        currentPageCanvas.renderPage();
        nextPageCanvas.renderPage();

        currentPageCanvasX = 0;

        repaintProgressBar = true;

        mode = MODE_PAGE_READING;
        
        repaint();
        serviceRepaints();
    }

    private void reflowPages() {
        int mode_ = mode;
        mode = MODE_PAGE_LOADING;
        repaint();
        serviceRepaints();
        chapterBooklet = new Booklet(currentPageCanvas.getWidth(),
                currentPageCanvas.getHeight(), fontPlain, fontItalic,
                hyphenator, currentBook.getArchive(),
                currentBook.getCurrentChapter());
        pagesCount = chapterBooklet.getPagesCount() - 3;
        mode = mode_;
    }

    public synchronized final void openLibrary() {
        app.switchDisplayable(null, app.getFileBrowser());
    }
    
    public synchronized final void cycleColorProfiles() {
        currentProfile = currentProfile.other;
        applyColorProfile();
    }

    public synchronized final ColorProfile getCurrentProfile() {
        return currentProfile;
    }

    public synchronized final void applyColorProfile() {

        //apply to buttons
        for (int i=0; i<buttons.length; i++) {
            buttons[i].setColor(currentProfile.getColor(ColorProfile.MENU_BUTTONS_COLOR));
        }
        repaintButtons = true;

        //apply to cursor
        waitCursor.setColor(currentProfile.getColor(ColorProfile.CURSOR_WAIT_COLOR));

        //apply to status bar
        repaintStatusBar = true;

        //apply to pages
        if (currentPageCanvas != null) {
            renderPages();
        }
    }

    private void loadFont() {
        int currentFontSize = FONT_SIZES[currentFontSizeIndex];
        fontPlain = loadFont("droid-serif_" + currentFontSize);
        fontItalic = loadFont("droid-serif_it_" + currentFontSize);
    }

    private void cycleFontSizes() {
        if (currentFontSizeIndex == 0) {
            fontGrowing = true;
        }

        if (currentFontSizeIndex == FONT_SIZES.length-1) {
            fontGrowing = false;
        }

        if (fontGrowing) {
            currentFontSizeIndex++;
        } else {
            currentFontSizeIndex--;
        }
        
        loadFont();
        reflowPages();
        goToPosition(currentBook.getCurrentChapter(), currentPageCanvas.page.getStart());
    }

    private void loadStatusFont() {
        fontStatus = loadFont("status");
    }

    private AlbiteFont loadFont(String fontName) {
        AlbiteFont font;
        try {
            font = new AlbiteFont(fontName);
        } catch (IOException ioe) {
            throw new RuntimeException("Couldn't load font.");
        } catch (AlbiteFontException afe) {
            throw new RuntimeException("Couldn't load font.");
        }
        return font;
    }

    public synchronized void hideNotify() {
        //TODO: suspend safely!
    }

    public synchronized void showNotify() {
        // force repaint of menu items
        repaintButtons = true;
        repaintStatusBar = true;
    }

    private void openRMSAndLoadData() {
        try {
            rs = RecordStore.openRecordStore("bookcanvas",true);

            if (rs.getNumRecords() > 0) {
                //deserialize first record
                byte[] data = rs.getRecord(1);
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(data));
                try {
                    //load profiles
                    ColorProfile currentProfile_ = ColorProfile.findProfileByName(din.readUTF());
                    ColorProfile otherProfile_ = ColorProfile.findProfileByName(din.readUTF());
                    currentProfile_.link(otherProfile_);
                    currentProfile = currentProfile_;

                    //load fonts
                    currentFontSizeIndex = din.readByte();

                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }
            }

        } catch (RecordStoreException rse) {
            //no saving is possible
            rse.printStackTrace();
        }
    }

    public synchronized final void saveOptionsToRMS() {
        //If bookCanvas has been opened AT ALL
        if (isBookOpen()) {
            try {
                //serialize first record
                ByteArrayOutputStream boas = new ByteArrayOutputStream();
                DataOutputStream dout = new DataOutputStream(boas);
                try {
                    //save profiles
                    dout.writeUTF(currentProfile.name);
                    dout.writeUTF(currentProfile.other.name);

                    //save fonts
                    dout.writeByte(currentFontSizeIndex);
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }

                byte[] data = boas.toByteArray();

                if (rs.getNumRecords() > 0) {
                    rs.setRecord(1, data, 0, data.length);
                } else {
                    rs.addRecord(data, 0, data.length);
                }
            } catch (RecordStoreException rse) {
                //no saving is possible
                rse.printStackTrace();
            }
        }
    }

    private synchronized void closeRMS() {
        try {
            rs.closeRecordStore();

        } catch (RecordStoreException rse) {
            //no saving is possible
            rse.printStackTrace();
        }
    }

    public synchronized final void close() {
        timer.cancel();
        saveOptionsToRMS();
        closeRMS();
        closeBook();
    }

    public synchronized final int getOrientation() {
        return orientation;
    }

    public synchronized final void updateClock() {
        repaintClock = true;
        repaint();
    }

    public synchronized final int getStatusBarHeight() {
        return statusBarHeight;
    }
}