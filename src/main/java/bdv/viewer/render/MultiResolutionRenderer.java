package bdv.viewer.render;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;

import net.imglib2.Dimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.Volatile;
import net.imglib2.converter.Converter;
import net.imglib2.display.screenimage.awt.ARGBScreenImage;
import net.imglib2.img.cell.CellImg;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.ui.PainterThread;
import net.imglib2.ui.RenderTarget;
import net.imglib2.ui.Renderer;
import net.imglib2.ui.SimpleInterruptibleProjector;
import net.imglib2.ui.util.GuiUtil;
import bdv.img.cache.Cache;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.state.SourceState;
import bdv.viewer.state.ViewerState;

/**
 * A {@link Renderer} that uses a coarse-to-fine rendering scheme. First, a
 * small {@link BufferedImage} at a fraction of the canvas resolution is
 * rendered. Then, increasingly larger images are rendered, until the full
 * canvas resolution is reached.
 * <p>
 * When drawing the low-resolution {@link BufferedImage} to the screen, they
 * will be scaled up by Java2D to the full canvas size, which is relatively
 * fast. Rendering the small, low-resolution images is usually very fast, such
 * that the display is very interactive while the user changes the viewing
 * transformation for example. When the transformation remains fixed for a
 * longer period, higher-resolution details are filled in successively.
 * <p>
 * The renderer allocates a {@link BufferedImage} for each of a predefined set
 * of <em>screen scales</em> (a screen scale of 1 means that 1 pixel in the
 * screen image is displayed as 1 pixel on the canvas, a screen scale of 0.5
 * means 1 pixel in the screen image is displayed as 2 pixel on the canvas,
 * etc.)
 * <p>
 * At any time, one of these screen scales is selected as the
 * <em>highest screen scale</em>. Rendering starts with this highest screen
 * scale and then proceeds to lower screen scales (higher resolution images).
 * Unless the highest screen scale is currently rendering,
 * {@link #requestRepaint() repaint request} will cancel rendering, such that
 * display remains interactive.
 * <p>
 * The renderer tries to maintain a per-frame rendering time close to a desired
 * number of <code>targetRenderNanos</code> nanoseconds. If the rendering time
 * (in nanoseconds) for the (currently) highest scaled screen image is above
 * this threshold, a coarser screen scale is chosen as the highest screen scale
 * to use. Similarly, if the rendering time for the (currently) second-highest
 * scaled screen image is below this threshold, this finer screen scale chosen
 * as the highest screen scale to use.
 * <p>
 * The renderer uses multiple threads (if desired) and double-buffering (if
 * desired).
 * <p>
 * Double buffering means that three {@link BufferedImage BufferedImages} are
 * created for every screen scale. After rendering the first one of them and
 * setting it to the {@link RenderTarget}, next time, rendering goes to the
 * second one, then to the third. The {@link RenderTarget} will always have a
 * complete image, which is not rendered to while it is potentially drawn to the
 * screen. When setting an image to the {@link RenderTarget}, the
 * {@link RenderTarget} will release one of the previously set images to be
 * rendered again. Thus, rendering will not interfere with painting the
 * {@link BufferedImage} to the canvas.
 * <p>
 * The renderer supports rendering of {@link Volatile} sources. In each
 * rendering pass, all currently valid data for the best fitting mipmap level
 * and all coarser levels is rendered to a {@link #renderImages temporary image}
 * for each visible source. Then the temporary images are combined to the final
 * image for display. The number of passes required until all data is valid
 * might differ between visible sources.
 * <p>
 * Rendering timing is tied to a {@link Cache} control for IO budgeting, etc.
 *
 * @author Tobias Pietzsch <tobias.pietzsch@gmail.com>
 */
public class MultiResolutionRenderer
{
	/**
	 * Receiver for the {@link BufferedImage BufferedImages} that we render.
	 */
	protected final RenderTarget display;

	/**
	 * Thread that triggers repainting of the display.
	 * Requests for repainting are send there.
	 */
	protected final PainterThread painterThread;

	/**
	 * Currently active projector, used to re-paint the display. It maps the
	 * {@link #source} data to {@link #screenImage}.
	 */
	protected VolatileProjector projector;

	/**
	 * The index of the screen scale of the {@link #projector current projector}.
	 */
	protected int currentScreenScaleIndex;

	/**
	 * Whether double buffering is used.
	 */
	protected final boolean doubleBuffered;

	/**
	 * Double-buffer index of next {@link #screenImages image} to render.
	 */
	protected final ArrayDeque< Integer > renderIdQueue;

	/**
	 * Maps from {@link BufferedImage} to double-buffer index.
	 * Needed for double-buffering.
	 */
	protected final HashMap< BufferedImage, Integer > bufferedImageToRenderId;

	/**
	 * Used to render an individual source. One image per screen resolution and
	 * visible source. First index is screen scale, second index is index in
	 * list of visible sources.
	 */
	protected ARGBScreenImage[][] renderImages;

	/**
	 * Storage for mask images of {@link VolatileHierarchyProjector}.
	 * One array per visible source. (First) index is index in list of visible sources.
	 */
	protected byte[][] renderMaskArrays;

	/**
	 * Used to render the image for display. Two images per screen resolution
	 * if double buffering is enabled. First index is screen scale, second index is
	 * double-buffer.
	 */
	protected ARGBScreenImage[][] screenImages;

	/**
	 * {@link BufferedImage}s wrapping the data in the {@link #screenImages}.
	 * First index is screen scale, second index is double-buffer.
	 */
	protected BufferedImage[][] bufferedImages;

	/**
	 * Scale factors from the {@link #display viewer canvas} to the
	 * {@link #screenImages}.
	 *
	 * A scale factor of 1 means 1 pixel in the screen image is displayed as 1
	 * pixel on the canvas, a scale factor of 0.5 means 1 pixel in the screen
	 * image is displayed as 2 pixel on the canvas, etc.
	 */
	protected final double[] screenScales;

	/**
	 * The scale transformation from viewer to {@link #screenImages screen
	 * image}. Each transformations corresponds to a {@link #screenScales screen
	 * scale}.
	 */
	protected AffineTransform3D[] screenScaleTransforms;

	/**
	 * If the rendering time (in nanoseconds) for the (currently) highest scaled
	 * screen image is above this threshold, increase the
	 * {@link #maxScreenScaleIndex index} of the highest screen scale to use.
	 * Similarly, if the rendering time for the (currently) second-highest
	 * scaled screen image is below this threshold, decrease the
	 * {@link #maxScreenScaleIndex index} of the highest screen scale to use.
	 */
	protected final long targetRenderNanos;

	/**
	 * The index of the (coarsest) screen scale with which to start rendering.
	 * Once this level is painted, rendering proceeds to lower screen scales
	 * until index 0 (full resolution) has been reached. While rendering, the
	 * maxScreenScaleIndex is adapted such that it is the highest index for
	 * which rendering in {@link #targetRenderNanos} nanoseconds is still
	 * possible.
	 */
	protected int maxScreenScaleIndex;

	/**
	 * The index of the screen scale which should be rendered next.
	 */
	protected int requestedScreenScaleIndex;

	/**
	 * Whether the current rendering operation may be cancelled (to start a
	 * new one). Rendering may be cancelled unless we are rendering at
	 * coarsest screen scale and coarsest mipmap level.
	 */
	protected volatile boolean renderingMayBeCancelled;

	/**
	 * How many threads to use for rendering.
	 */
	protected final int numRenderingThreads;

	/**
	 * {@link ExecutorService} used for rendering.
	 */
	protected final ExecutorService renderingExecutorService;

	/**
	 * Controls IO budgeting and fetcher queue.
	 */
	protected final Cache cache;

	/**
	 * Whether volatile versions of sources should be used if available.
	 */
	protected final boolean useVolatileIfAvailable;

	/**
	 * Whether a repaint was {@link #requestRepaint() requested}. This will
	 * cause {@link Cache#prepareNextFrame()}.
	 */
	protected boolean newFrameRequest;

	/**
	 * The timepoint for which last a projector was
	 * {@link #createProjector(ViewerState, int, ARGBScreenImage) created}.
	 */
	protected int previousTimepoint;

	// TODO: should be settable
	protected long[] iobudget = new long[] { 100l * 1000000l,  10l * 1000000l };

	// TODO: should be settable
	protected boolean prefetchCells = true;

	/**
	 * @param display
	 *            The canvas that will display the images we render.
	 * @param painterThread
	 *            Thread that triggers repainting of the display. Requests for
	 *            repainting are send there.
	 * @param screenScales
	 *            Scale factors from the viewer canvas to screen images of
	 *            different resolutions. A scale factor of 1 means 1 pixel in
	 *            the screen image is displayed as 1 pixel on the canvas, a
	 *            scale factor of 0.5 means 1 pixel in the screen image is
	 *            displayed as 2 pixel on the canvas, etc.
	 * @param targetRenderNanos
	 *            Target rendering time in nanoseconds. The rendering time for
	 *            the coarsest rendered scale should be below this threshold.
	 * @param doubleBuffered
	 *            Whether to use double buffered rendering.
	 * @param numRenderingThreads
	 *            How many threads to use for rendering.
	 * @param renderingExecutorService
	 *            if non-null, this is used for rendering. Note, that it is
	 *            still important to supply the numRenderingThreads parameter,
	 *            because that is used to determine into how many sub-tasks
	 *            rendering is split.
	 * @param useVolatileIfAvailable
	 *            whether volatile versions of sources should be used if
	 *            available.
	 * @param cache
	 *            the cache controls IO budgeting and fetcher queue.
	 */
	public MultiResolutionRenderer(
			final RenderTarget display,
			final PainterThread painterThread,
			final double[] screenScales,
			final long targetRenderNanos,
			final boolean doubleBuffered,
			final int numRenderingThreads,
			final ExecutorService renderingExecutorService,
			final boolean useVolatileIfAvailable,
			final Cache cache )
	{
		this.display = display;
		this.painterThread = painterThread;
		projector = null;
		currentScreenScaleIndex = -1;
		this.screenScales = screenScales.clone();
		this.doubleBuffered = doubleBuffered;
		renderIdQueue = new ArrayDeque< Integer >();
		bufferedImageToRenderId = new HashMap< BufferedImage, Integer >();
		renderImages = new ARGBScreenImage[ screenScales.length ][ 0 ];
		renderMaskArrays = new byte[ 0 ][];
		screenImages = new ARGBScreenImage[ screenScales.length ][ 3 ];
		bufferedImages = new BufferedImage[ screenScales.length ][ 3 ];
		screenScaleTransforms = new AffineTransform3D[ screenScales.length ];

		this.targetRenderNanos = targetRenderNanos;

		maxScreenScaleIndex = screenScales.length - 1;
		requestedScreenScaleIndex = maxScreenScaleIndex;
		renderingMayBeCancelled = true;
		this.numRenderingThreads = numRenderingThreads;
		this.renderingExecutorService = renderingExecutorService;
		this.useVolatileIfAvailable = useVolatileIfAvailable;
		this.cache = cache;
		newFrameRequest = false;
		previousTimepoint = -1;
	}

	/**
	 * Check whether the size of the display component was changed and
	 * recreate {@link #screenImages} and {@link #screenScaleTransforms} accordingly.
	 *
	 * @return whether the size was changed.
	 */
	protected synchronized boolean checkResize()
	{
		final int componentW = display.getWidth();
		final int componentH = display.getHeight();
		if ( screenImages[ 0 ][ 0 ] == null || screenImages[ 0 ][ 0 ].dimension( 0 ) * screenScales[ 0 ] != componentW || screenImages[ 0 ][ 0 ].dimension( 1 )  * screenScales[ 0 ] != componentH )
		{
			renderIdQueue.clear();
			renderIdQueue.addAll( Arrays.asList( 0, 1, 2 ) );
			bufferedImageToRenderId.clear();
			for ( int i = 0; i < screenScales.length; ++i )
			{
				final double screenToViewerScale = screenScales[ i ];
				final int w = ( int ) ( screenToViewerScale * componentW );
				final int h = ( int ) ( screenToViewerScale * componentH );
				if ( doubleBuffered )
				{
					for ( int b = 0; b < ( doubleBuffered ? 3 : 1 ); ++b )
					{
						// reuse storage arrays of level 0 (highest resolution)
						screenImages[ i ][ b ] = ( i == 0 ) ?
								new ARGBScreenImage( w, h ) :
								new ARGBScreenImage( w, h, screenImages[ 0 ][ b ].getData() );
						final BufferedImage bi = GuiUtil.getBufferedImage( screenImages[ i ][ b ] );
						bufferedImages[ i ][ b ] = bi;
						bufferedImageToRenderId.put( bi, b );
					}
				}
				else
				{
					screenImages[ i ][ 0 ] = new ARGBScreenImage( w, h );
					bufferedImages[ i ][ 0 ] = GuiUtil.getBufferedImage( screenImages[ i ][ 0 ] );
				}
				final AffineTransform3D scale = new AffineTransform3D();
				final double xScale = ( double ) w / componentW;
				final double yScale = ( double ) h / componentH;
				scale.set( xScale, 0, 0 );
				scale.set( yScale, 1, 1 );
				scale.set( 0.5 * xScale - 0.5, 0, 3 );
				scale.set( 0.5 * yScale - 0.5, 1, 3 );
				screenScaleTransforms[ i ] = scale;
			}

			return true;
		}
		return false;
	}

	protected synchronized boolean checkRenewRenderImages( final int numVisibleSources )
	{
		final int n = numVisibleSources > 1 ? numVisibleSources : 0;
		if ( n != renderImages[ 0 ].length ||
				( n != 0 &&
					( renderImages[ 0 ][ 0 ].dimension( 0 ) != screenImages[ 0 ][ 0 ].dimension( 0 ) ||
					  renderImages[ 0 ][ 0 ].dimension( 1 ) != screenImages[ 0 ][ 0 ].dimension( 1 ) ) ) )
		{
			renderImages = new ARGBScreenImage[ screenScales.length ][ numVisibleSources ];
			for ( int i = 0; i < screenScales.length; ++i )
			{
				final int w = ( int ) screenImages[ i ][ 0 ].dimension( 0 );
				final int h = ( int ) screenImages[ i ][ 0 ].dimension( 1 );
				for ( int j = 0; j < numVisibleSources; ++j )
				{
					renderImages[ i ][ j ] = ( i == 0 ) ?
						new ARGBScreenImage( w, h ) :
						new ARGBScreenImage( w, h, renderImages[ 0 ][ j ].getData() );
				}
			}
			return true;
		}
		return false;
	}

	protected synchronized boolean checkRenewMaskArrays( final int numVisibleSources )
	{
		if ( numVisibleSources != renderMaskArrays.length ||
				( numVisibleSources != 0 &&	( renderMaskArrays[ 0 ].length < screenImages[ 0 ][ 0 ].size() ) ) )
		{
			final int size = ( int ) screenImages[ 0 ][ 0 ].size();
			renderMaskArrays = new byte[ numVisibleSources ][];
			for ( int j = 0; j < numVisibleSources; ++j )
				renderMaskArrays[ j ] = new byte[ size ];
			return true;
		}
		return false;
	}

	/**
	 * Render image at the {@link #requestedScreenScaleIndex requested screen
	 * scale} and the {@link #requestedMipmapLevel requested mipmap level}.
	 */
	public boolean paint( final ViewerState state )
	{
		if ( display.getWidth() <= 0 || display.getHeight() <= 0 )
			return false;

		final boolean resized = checkResize();

		final int numVisibleSources = state.getVisibleSourceIndices().size();
		checkRenewRenderImages( numVisibleSources );
		checkRenewMaskArrays( numVisibleSources );

		// the BufferedImage that is rendered to (to paint to the canvas)
		final BufferedImage bufferedImage;

		// the projector that paints to the screenImage.
		final VolatileProjector p;

		final boolean clearQueue;

		final boolean createProjector;

		synchronized ( this )
		{
			// Rendering may be cancelled unless we are rendering at coarsest
			// screen scale and coarsest mipmap level.
			renderingMayBeCancelled = ( requestedScreenScaleIndex < maxScreenScaleIndex );

			clearQueue = newFrameRequest;
			if ( clearQueue )
				cache.prepareNextFrame();
			createProjector = newFrameRequest || resized || ( requestedScreenScaleIndex != currentScreenScaleIndex );
			newFrameRequest = false;

			if ( createProjector )
			{
				final int renderId = renderIdQueue.peek();
				currentScreenScaleIndex = requestedScreenScaleIndex;
				bufferedImage = bufferedImages[ currentScreenScaleIndex ][ renderId ];
				final ARGBScreenImage screenImage = screenImages[ currentScreenScaleIndex ][ renderId ];
				p = createProjector( state, currentScreenScaleIndex, screenImage );
				projector = p;
			}
			else
			{
				bufferedImage = null;
				p = projector;
			}
		}

		// try rendering
		final boolean success = p.map( createProjector );
		final long rendertime = p.getLastFrameRenderNanoTime();

		synchronized ( this )
		{
			// if rendering was not cancelled...
			if ( success )
			{
				if ( createProjector )
				{
					final BufferedImage bi = display.setBufferedImage( bufferedImage );
					if ( doubleBuffered )
					{
						renderIdQueue.pop();
						final Integer id = bufferedImageToRenderId.get( bi );
						if ( id != null )
							renderIdQueue.add( id );
					}

					if ( currentScreenScaleIndex == maxScreenScaleIndex )
					{
						if ( rendertime > targetRenderNanos && maxScreenScaleIndex < screenScales.length - 1 )
							maxScreenScaleIndex++;
						else if ( rendertime < targetRenderNanos / 3 && maxScreenScaleIndex > 0 )
							maxScreenScaleIndex--;
					}
					else if ( currentScreenScaleIndex == maxScreenScaleIndex - 1 )
					{
						if ( rendertime < targetRenderNanos && maxScreenScaleIndex > 0 )
							maxScreenScaleIndex--;
					}
//					System.out.println( String.format( "rendering:%4d ms", rendertime / 1000000 ) );
//					System.out.println( "scale = " + currentScreenScaleIndex );
//					System.out.println( "maxScreenScaleIndex = " + maxScreenScaleIndex + "  (" + screenImages[ maxScreenScaleIndex ][ 0 ].dimension( 0 ) + " x " + screenImages[ maxScreenScaleIndex ][ 0 ].dimension( 1 ) + ")" );
				}

				if ( currentScreenScaleIndex > 0 )
					requestRepaint( currentScreenScaleIndex - 1 );
				else if ( !p.isValid() )
				{
					try
					{
						Thread.sleep( 1 );
					}
					catch ( final InterruptedException e )
					{}
					requestRepaint( currentScreenScaleIndex );
				}
			}
		}

		return success;
	}

	/**
	 * Request a repaint of the display from the painter thread, with maximum
	 * screen scale index and mipmap level.
	 */
	public synchronized void requestRepaint()
	{
		newFrameRequest = true;
		requestRepaint( maxScreenScaleIndex );
	}

	/**
	 * Request a repaint of the display from the painter thread. The painter
	 * thread will trigger a {@link #paint()} as soon as possible (that is,
	 * immediately or after the currently running {@link #paint()} has
	 * completed).
	 */
	public synchronized void requestRepaint( final int screenScaleIndex )
	{
		if ( renderingMayBeCancelled && projector != null )
			projector.cancel();
		requestedScreenScaleIndex = screenScaleIndex;
		painterThread.requestRepaint();
	}

	private VolatileProjector createProjector(
			final ViewerState viewerState,
			final int screenScaleIndex,
			final ARGBScreenImage screenImage )
	{
		synchronized ( viewerState )
		{
			cache.initIoTimeBudget( null ); // clear time budget such that prefetching doesn't wait for loading blocks.
			final List< SourceState< ? > > sources = viewerState.getSources();
			final List< Integer > visibleSourceIndices = viewerState.getVisibleSourceIndices();
			VolatileProjector projector;
			if ( visibleSourceIndices.isEmpty() )
				projector = new EmptyProjector< ARGBType >( screenImage );
			else if ( visibleSourceIndices.size() == 1 )
			{
				final int i = visibleSourceIndices.get( 0 );
				projector = createSingleSourceProjector( viewerState, sources.get( i ), i, currentScreenScaleIndex, screenImage, renderMaskArrays[ 0 ] );
			}
			else
			{
				final ArrayList< VolatileProjector > sourceProjectors = new ArrayList< VolatileProjector >();
				final ArrayList< ARGBScreenImage > sourceImages = new ArrayList< ARGBScreenImage >();
				int j = 0;
				for ( final int i : visibleSourceIndices )
				{
					final ARGBScreenImage renderImage = renderImages[ currentScreenScaleIndex ][ j ];
					final byte[] maskArray = renderMaskArrays[ j ];
					++j;
					final VolatileProjector p = createSingleSourceProjector(
							viewerState, sources.get( i ), i, currentScreenScaleIndex,
							renderImage, maskArray );
					sourceProjectors.add( p );
					sourceImages.add( renderImage );
				}
				projector = new AccumulateProjectorARGB( sourceProjectors, sourceImages, screenImage, numRenderingThreads );
			}
			previousTimepoint = viewerState.getCurrentTimepoint();
			cache.initIoTimeBudget( iobudget );
			return projector;
		}
	}

	private static class SimpleVolatileProjector< A, B > extends SimpleInterruptibleProjector< A, B > implements VolatileProjector
	{
		private boolean valid = false;

		public SimpleVolatileProjector(
				final RandomAccessible< A > source,
				final Converter< ? super A, B > converter,
				final RandomAccessibleInterval< B > target,
				final int numThreads )
		{
			super( source, converter, target, numThreads );
		}

		@Override
		public boolean map( final boolean clearUntouchedTargetPixels )
		{
			final boolean success = super.map();
			valid |= success;
			return success;
		}

		@Override
		public boolean isValid()
		{
			return valid;
		}
	}

	private < T > VolatileProjector createSingleSourceProjector(
			final ViewerState viewerState,
			final SourceState< T > source,
			final int sourceIndex,
			final int screenScaleIndex,
			final ARGBScreenImage screenImage,
			final byte[] maskArray )
	{
		if ( useVolatileIfAvailable && source.asVolatile() != null )
		{
			return createSingleSourceVolatileProjector( viewerState, source.asVolatile(), sourceIndex, screenScaleIndex, screenImage, maskArray );
		}
		else
		{
			final AffineTransform3D screenScaleTransform = screenScaleTransforms[ currentScreenScaleIndex ];
			final int bestLevel = viewerState.getBestMipMapLevel( screenScaleTransform, sourceIndex );
			return new SimpleVolatileProjector< T, ARGBType >(
					getTransformedSource( viewerState, source.getSpimSource(), screenScaleTransform, bestLevel ),
					source.getConverter(), screenImage, numRenderingThreads );
		}
	}

	private < T extends Volatile< ? > > VolatileProjector createSingleSourceVolatileProjector(
			final ViewerState viewerState,
			final SourceState< T > source,
			final int sourceIndex,
			final int screenScaleIndex,
			final ARGBScreenImage screenImage,
			final byte[] maskArray )
	{
		final AffineTransform3D screenScaleTransform = screenScaleTransforms[ currentScreenScaleIndex ];
		final ArrayList< RandomAccessible< T > > levels = new ArrayList< RandomAccessible< T > >();
		final int bestLevel = viewerState.getBestMipMapLevel( screenScaleTransform, sourceIndex );
		final int nLevels = source.getSpimSource().getNumMipmapLevels();
		final Source< T > spimSource = source.getSpimSource();
		final int t = viewerState.getCurrentTimepoint();
		if ( t != previousTimepoint )
		{
			// When scrolling through time, we often get frames for which no
			// data was loaded yet. To speed up rendering in these cases, use
			// only two mipmap levels: the optimal and the coarsest. By doing
			// this, we require at most two passes over the image at the expense
			// of ignoring data present in intermediate mipmap levels. The
			// assumption is, that we will either be moving back and forth
			// between images that have all data present already or that we move
			// to a new image with no data present at all.
			levels.add( getTransformedSource( viewerState, spimSource, screenScaleTransform, bestLevel ) );
			if ( nLevels - 1 != bestLevel )
				levels.add( getTransformedSource( viewerState, spimSource, screenScaleTransform, nLevels - 1 ) );

			if ( prefetchCells )
			{
				if ( nLevels - 1 != bestLevel )
					prefetch( viewerState, spimSource, screenScaleTransform, nLevels - 1, screenImage );
				prefetch( viewerState, spimSource, screenScaleTransform, bestLevel, screenImage );
			}

			// slight abuse of newFrameRequest: we only want this two-pass
			// rendering to happen once then switch to normal multi-pass
			// rendering if we remain longer on this frame.
			newFrameRequest = true;
		}
		else
		{
			for ( int i = bestLevel; i < nLevels; ++i )
				levels.add( getTransformedSource( viewerState, spimSource, screenScaleTransform, i ) );

			if ( prefetchCells )
				for ( int i = nLevels - 1; i >= bestLevel; --i )
					prefetch( viewerState, spimSource, screenScaleTransform, i, screenImage );
		}
//		for ( int i = bestLevel - 1; i >= 0; --i )
//			levels.add( getTransformedSource( viewerState, spimSource, screenScaleTransform, i ) );
		return new VolatileHierarchyProjector< T, ARGBType >( levels, source.getConverter(), screenImage, numRenderingThreads, renderingExecutorService );
	}

	private static < T > RandomAccessible< T > getTransformedSource( final ViewerState viewerState, final Source< T > source, final AffineTransform3D screenScaleTransform, final int mipmapIndex )
	{
		final int timepoint = viewerState.getCurrentTimepoint();
		final Interpolation interpolation = viewerState.getInterpolation();
		final RealRandomAccessible< T > img = source.getInterpolatedSource( timepoint, mipmapIndex, interpolation );

		final AffineTransform3D sourceToScreen = new AffineTransform3D();
		viewerState.getViewerTransform( sourceToScreen );
		sourceToScreen.concatenate( source.getSourceTransform( timepoint, mipmapIndex ) );
		sourceToScreen.preConcatenate( screenScaleTransform );

		return RealViews.constantAffine( img, sourceToScreen );
	}

	private static < T > void prefetch(
			final ViewerState viewerState,
			final Source< T > source,
			final AffineTransform3D screenScaleTransform,
			final int mipmapIndex,
			final Dimensions screenInterval )
	{
		final int timepoint = viewerState.getCurrentTimepoint();
		final RandomAccessibleInterval< T > img = source.getSource( timepoint, mipmapIndex );
		if ( CellImg.class.isInstance( img ) )
		{
			final CellImg< ?, ?, ? > cellImg = ( CellImg< ?, ?, ? > ) img;
			final int[] cellDimensions = new int[ 3 ];
			cellImg.getCells().cellDimensions( cellDimensions );
			final long[] dimensions = new long[ 3 ];
			cellImg.dimensions( dimensions );
			final RandomAccess< ? > cellsRandomAccess = cellImg.getCells().randomAccess();

			final Interpolation interpolation = viewerState.getInterpolation();

			final AffineTransform3D sourceToScreen = new AffineTransform3D();
			viewerState.getViewerTransform( sourceToScreen );
			sourceToScreen.concatenate( source.getSourceTransform( timepoint, mipmapIndex ) );
			sourceToScreen.preConcatenate( screenScaleTransform );

			Prefetcher.fetchCells( sourceToScreen, cellDimensions, dimensions, screenInterval, interpolation, cellsRandomAccess );
		}
	}
}
