package bdv;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import javax.swing.ActionMap;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.filechooser.FileFilter;

import mpicbg.spim.data.SequenceDescription;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import bdv.export.ProgressWriter;
import bdv.export.ProgressWriterConsole;
import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.tools.HelpDialog;
import bdv.tools.InitializeViewerState;
import bdv.tools.RecordMovieDialog;
import bdv.tools.VisibilityAndGroupingDialog;
import bdv.tools.brightness.BrightnessDialog;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.MinMaxGroup;
import bdv.tools.brightness.RealARGBColorConverterSetup;
import bdv.tools.brightness.SetupAssignments;
import bdv.tools.crop.CropDialog;
import bdv.tools.transformation.ManualTransformation;
import bdv.tools.transformation.ManualTransformationEditor;
import bdv.tools.transformation.TransformedSource;
import bdv.util.KeyProperties;
import bdv.viewer.NavigationActions;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerFrame;
import bdv.viewer.ViewerPanel;

public class BigDataViewer
{
	final ViewerFrame viewerFrame;

	final ViewerPanel viewer;

	final SetupAssignments setupAssignments;

	final ManualTransformation manualTransformation;

	final BrightnessDialog brightnessDialog;

	final CropDialog cropDialog;

	final RecordMovieDialog movieDialog;

	final VisibilityAndGroupingDialog activeSourcesDialog;

	final HelpDialog helpDialog;

	final ManualTransformationEditor manualTransformationEditor;

	final JFileChooser fileChooser;

	File proposedSettingsFile;

	public void toggleManualTransformation()
	{
		manualTransformationEditor.toggle();
	}

	private BigDataViewer( final String xmlFilename, final ProgressWriter progressWriter ) throws InstantiationException, IllegalAccessException, ClassNotFoundException, JDOMException, IOException
	{
		final int width = 800;
		final int height = 600;

		final SequenceViewsLoader loader = new SequenceViewsLoader( xmlFilename );
		final SequenceDescription seq = loader.getSequenceDescription();

		final ArrayList< ConverterSetup > converterSetups = new ArrayList< ConverterSetup >();
		final ArrayList< SourceAndConverter< ? > > sources = new ArrayList< SourceAndConverter< ? > >();
		for ( int setup = 0; setup < seq.numViewSetups(); ++setup )
		{
			final RealARGBColorConverter< VolatileUnsignedShortType > vconverter = new RealARGBColorConverter< VolatileUnsignedShortType >( 0, 65535 );
			vconverter.setColor( new ARGBType( ARGBType.rgba( 255, 255, 255, 255 ) ) );
			final RealARGBColorConverter< UnsignedShortType > converter = new RealARGBColorConverter< UnsignedShortType >( 0, 65535 );
			converter.setColor( new ARGBType( ARGBType.rgba( 255, 255, 255, 255 ) ) );
			final VolatileSpimSource vs = new VolatileSpimSource( loader, setup, "angle " + seq.setups.get( setup ).getAngle() );
			final SpimSource s = vs.nonVolatile();

			// Decorate each source with an extra transformation, that can be edited manually in this viewer.
			final TransformedSource< VolatileUnsignedShortType > tvs = new TransformedSource< VolatileUnsignedShortType >( vs );
			final TransformedSource< UnsignedShortType > ts = new TransformedSource< UnsignedShortType >( s, tvs );

			final SourceAndConverter< VolatileUnsignedShortType > vsoc = new SourceAndConverter< VolatileUnsignedShortType >( tvs, vconverter );
			final SourceAndConverter< UnsignedShortType > soc = new SourceAndConverter< UnsignedShortType >( ts, converter, vsoc );

			sources.add( soc );
			converterSetups.add( new RealARGBColorConverterSetup( setup, converter, vconverter ) );
		}

		viewerFrame = new ViewerFrame( width, height, sources, seq.numTimepoints(),
				( ( Hdf5ImageLoader ) seq.imgLoader ).getCache() );
		viewer = viewerFrame.getViewerPanel();

		for ( final ConverterSetup cs : converterSetups )
			if ( RealARGBColorConverterSetup.class.isInstance( cs ) )
				( ( RealARGBColorConverterSetup ) cs ).setViewer( viewer );

		manualTransformation = new ManualTransformation( viewer );
		manualTransformationEditor = new ManualTransformationEditor( viewer, viewerFrame.getKeybindings() );

		setupAssignments = new SetupAssignments( converterSetups, 0, 65535 );
		final MinMaxGroup group = setupAssignments.getMinMaxGroups().get( 0 );
		for ( final ConverterSetup setup : setupAssignments.getConverterSetups() )
			setupAssignments.moveSetupToGroup( setup, group );

		brightnessDialog = new BrightnessDialog( viewerFrame, setupAssignments );

		cropDialog = new CropDialog( viewerFrame, viewer, seq );

		movieDialog = new RecordMovieDialog( viewerFrame, viewer, progressWriter );
		viewer.getDisplay().addOverlayRenderer( movieDialog ); // this is just to get updates of window size

		activeSourcesDialog = new VisibilityAndGroupingDialog( viewerFrame, viewer.getVisibilityAndGrouping() );

		helpDialog = new HelpDialog( viewerFrame );

		fileChooser = new JFileChooser();
		fileChooser.setFileFilter( new FileFilter()
		{
			@Override
			public String getDescription()
			{
				return "xml files";
			}

			@Override
			public boolean accept( final File f )
			{
				if ( f.isDirectory() )
					return true;
				if ( f.isFile() )
				{
			        final String s = f.getName();
			        final int i = s.lastIndexOf('.');
			        if (i > 0 &&  i < s.length() - 1) {
			            final String ext = s.substring(i+1).toLowerCase();
			            return ext.equals( "xml" );
			        }
				}
				return false;
			}
		} );

		final KeyProperties keyProperties = KeyProperties.readPropertyFile();
		NavigationActions.installActionBindings( viewerFrame.getKeybindings(), viewer, keyProperties );
		BigDataViewerActions.installActionBindings( viewerFrame.getKeybindings(), this, keyProperties );

		final JMenuBar menubar = new JMenuBar();
		JMenu menu = new JMenu( "File" );
		menubar.add( menu );

		final ActionMap actionMap = viewerFrame.getKeybindings().getConcatenatedActionMap();
		final JMenuItem miLoadSettings = new JMenuItem( actionMap.get( BigDataViewerActions.LOAD_SETTINGS ) );
		miLoadSettings.setText( "Load settings" );
		menu.add( miLoadSettings );

		final JMenuItem miSaveSettings = new JMenuItem( actionMap.get( BigDataViewerActions.SAVE_SETTINGS ) );
		miSaveSettings.setText( "Save settings" );
		menu.add( miSaveSettings );

		menu = new JMenu( "Settings" );
		menubar.add( menu );

		final JMenuItem miBrightness = new JMenuItem( actionMap.get( BigDataViewerActions.BRIGHTNESS_SETTINGS ) );
		miBrightness.setText( "Brightness & Color" );
		menu.add( miBrightness );

		final JMenuItem miVisibility = new JMenuItem( actionMap.get( BigDataViewerActions.VISIBILITY_AND_GROUPING ) );
		miVisibility.setText( "Visibility & Grouping" );
		menu.add( miVisibility );

		menu = new JMenu( "Tools" );
		menubar.add( menu );

		final JMenuItem miCrop = new JMenuItem( actionMap.get( BigDataViewerActions.CROP ) );
		miCrop.setText( "Crop" );
		menu.add( miCrop );

		final JMenuItem miMovie = new JMenuItem( actionMap.get( BigDataViewerActions.RECORD_MOVIE ) );
		miMovie.setText( "Record Movie" );
		menu.add( miMovie );

		final JMenuItem miManualTransform = new JMenuItem( actionMap.get( BigDataViewerActions.MANUAL_TRANSFORM ) );
		miManualTransform.setText( "Manual Transform" );
		menu.add( miManualTransform );

		menu = new JMenu( "Help" );
		menubar.add( menu );

		final JMenuItem miHelp = new JMenuItem( actionMap.get( BigDataViewerActions.SHOW_HELP ) );
		miHelp.setText( "Show Help" );
		menu.add( miHelp );

		viewerFrame.setJMenuBar( menubar );

		viewerFrame.setVisible( true );

		InitializeViewerState.initTransform( viewer );

		if( ! tryLoadSettings( xmlFilename ) )
			InitializeViewerState.initBrightness( 0.001, 0.999, viewer, setupAssignments );

//		( ( Hdf5ImageLoader ) seq.imgLoader ).initCachedDimensionsFromHdf5( false );
	}

	boolean tryLoadSettings( final String xmlFilename )
	{
		proposedSettingsFile = null;
		if ( xmlFilename.endsWith( ".xml" ) )
		{
			final String settings = xmlFilename.substring( 0, xmlFilename.length() - ".xml".length() ) + ".settings" + ".xml";
			proposedSettingsFile = new File( settings );
			if ( proposedSettingsFile.isFile() )
			{
				try
				{
					loadSettings( settings );
					return true;
				}
				catch ( final Exception e )
				{
					e.printStackTrace();
				}
			}
		}
		return false;
	}

	void saveSettings()
	{
		fileChooser.setSelectedFile( proposedSettingsFile );
		final int returnVal = fileChooser.showSaveDialog( null );
		if ( returnVal == JFileChooser.APPROVE_OPTION )
		{
			proposedSettingsFile = fileChooser.getSelectedFile();
			try
			{
				saveSettings( proposedSettingsFile.getCanonicalPath() );
			}
			catch ( final IOException e )
			{
				e.printStackTrace();
			}
		}
	}

	void saveSettings( final String xmlFilename ) throws IOException
	{
		final Element root = new Element( "Settings" );
		root.addContent( viewer.stateToXml() );
		root.addContent( setupAssignments.toXml() );
		root.addContent( manualTransformation.toXml() );
		final Document doc = new Document( root );
		final XMLOutputter xout = new XMLOutputter( Format.getPrettyFormat() );
		xout.output( doc, new FileWriter( xmlFilename ) );
	}

	void loadSettings()
	{
		fileChooser.setSelectedFile( proposedSettingsFile );
		final int returnVal = fileChooser.showOpenDialog( null );
		if ( returnVal == JFileChooser.APPROVE_OPTION )
		{
			proposedSettingsFile = fileChooser.getSelectedFile();
			try
			{
				loadSettings( proposedSettingsFile.getCanonicalPath() );
			}
			catch ( final Exception e )
			{
				e.printStackTrace();
			}
		}
	}

	void loadSettings( final String xmlFilename ) throws IOException, JDOMException
	{
		final SAXBuilder sax = new SAXBuilder();
		final Document doc = sax.build( xmlFilename );
		final Element root = doc.getRootElement();
		viewer.stateFromXml( root );
		setupAssignments.restoreFromXml( root );
		manualTransformation.restoreFromXml( root );
		activeSourcesDialog.update();
		viewer.requestRepaint();
	}

	public static void view( final String filename, final ProgressWriter progressWriter ) throws InstantiationException, IllegalAccessException, ClassNotFoundException, JDOMException, IOException
	{
		new BigDataViewer( filename, progressWriter );
	}

	public static void main( final String[] args )
	{
		final String fn = "/Users/pietzsch/desktop/data/BDV130418A325/BDV130418A325_NoTempReg.xml";
//		final String fn = "/Users/pietzsch/Desktop/data/valia2/valia.xml";
//		final String fn = "/Users/pietzsch/workspace/data/fast fly/111010_weber/combined.xml";
//		final String fn = "/Users/pietzsch/workspace/data/mette/mette.xml";
//		final String fn = "/Users/tobias/Desktop/openspim.xml";
//		final String fn = "/Users/pietzsch/Desktop/data/fibsem.xml";
//		final String fn = "/Users/pietzsch/Desktop/url-valia.xml";
//		final String fn = "/Users/pietzsch/Desktop/data/Valia/valia.xml";
//		final String fn = "/Users/pietzsch/workspace/data/111010_weber_full.xml";
//		final String fn = "/Volumes/projects/tomancak_lightsheet/Mette/ZeissZ1SPIM/Maritigrella/021013_McH2BsGFP_CAAX-mCherry/11-use/hdf5/021013_McH2BsGFP_CAAX-mCherry-11-use.xml";
		try
		{
			System.setProperty("apple.laf.useScreenMenuBar", "true");
			new BigDataViewer( fn, new ProgressWriterConsole() );
		}
		catch ( final Exception e )
		{
			e.printStackTrace();
		}
	}
}
