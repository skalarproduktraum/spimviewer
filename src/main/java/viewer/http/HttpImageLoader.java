package viewer.http;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

import mpicbg.spim.data.View;
import mpicbg.spim.data.XmlHelpers;
import net.imglib2.img.Img;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.img.cell.CellImg;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import viewer.ViewerImgLoader;
import viewer.hdf5.Hdf5ImageLoader;
import viewer.hdf5.Hdf5ImageLoader.DimensionsInfo;
import viewer.hdf5.img.Hdf5Cell;
import viewer.hdf5.img.Hdf5GlobalCellCache;
import viewer.hdf5.img.Hdf5ImgCells;
import viewer.http.img.HttpGlobalCellCache;
import viewer.http.img.HttpShortArrayLoader;

public class HttpImageLoader implements ViewerImgLoader
{
	String baseUrl;

	protected HttpGlobalCellCache< ShortArray > cache;

	protected final ArrayList< double[][] > perSetupMipmapResolutions;

	public HttpImageLoader()
	{
		baseUrl = null;
		perSetupMipmapResolutions = new ArrayList< double[][] >();
	}

	@Override
	public void init( final Element elem, final File basePath )
	{
		baseUrl = elem.getElementsByTagName( "url" ).item( 0 ).getTextContent();
		try
		{
			open();
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( e );
		}
	}

	@Override
	public Element toXml( final Document doc, final File basePath )
	{
		final Element elem = doc.createElement( "ImageLoader" );
		elem.setAttribute( "class", getClass().getCanonicalName() );
		elem.appendChild( XmlHelpers.textElement( doc, "url", baseUrl ) );
		return elem;
	}

	@SuppressWarnings( "unchecked" )
	private void open() throws IOException, ClassNotFoundException
	{
		final URL url = new URL( baseUrl + "?p=init" );
		final ObjectInputStream os = new ObjectInputStream( url.openStream() );

		final int numTimepoints = os.readInt();

		perSetupMipmapResolutions.clear();
		perSetupMipmapResolutions.addAll( ( ArrayList< double[][] > ) os.readObject() );

		final int numSetups = perSetupMipmapResolutions.size();

		int maxNumLevels = 0;
		for ( int setup = 0; setup < numSetups; ++setup )
			for ( final double[][] mipmapResolutions : perSetupMipmapResolutions )
				if ( mipmapResolutions.length > maxNumLevels )
					maxNumLevels = mipmapResolutions.length;

		cache = new HttpGlobalCellCache< ShortArray >( new HttpShortArrayLoader( baseUrl ), numTimepoints, numSetups, maxNumLevels );
	}

	@Override
	public Img< FloatType > getImage( final View view )
	{
		throw new UnsupportedOperationException( "currently not used" );
	}

	@Override
	public Img< UnsignedShortType > getUnsignedShortImage( final View view )
	{
		return getUnsignedShortImage( view, 0 );
	}

	@Override
	public Img< UnsignedShortType > getUnsignedShortImage( final View view, final int level )
	{
		try
		{
			final URL url = new URL( String.format( "%s?p=dim/%d/%d/%d", baseUrl, view.getTimepointIndex(), view.getSetupIndex(), level ) );
			final ObjectInputStream os = new ObjectInputStream( url.openStream() );
			final Hdf5ImageLoader.DimensionsInfo info = ( DimensionsInfo ) os.readObject();

			final long[] dimensions = info.getDimensions();
			final int[] cellDimensions = info.getCellDimensions();

			final Hdf5GlobalCellCache< ShortArray >.Hdf5CellCache c = cache.new Hdf5CellCache( view.getTimepointIndex(), view.getSetupIndex(), level );
			final Hdf5ImgCells< ShortArray > cells = new Hdf5ImgCells< ShortArray >( c, 1, dimensions, cellDimensions );
			final CellImgFactory< UnsignedShortType > factory = null;
			final CellImg< UnsignedShortType, ShortArray, Hdf5Cell< ShortArray > > img = new CellImg< UnsignedShortType, ShortArray, Hdf5Cell< ShortArray > >( factory, cells );
			final UnsignedShortType linkedType = new UnsignedShortType( img );
			img.setLinkedType( linkedType );

			return img;
		}
		catch ( final MalformedURLException e )
		{
			e.printStackTrace();
		}
		catch ( final IOException e )
		{
			e.printStackTrace();
		}
		catch ( final ClassNotFoundException e )
		{
			e.printStackTrace();
		}
		return null;
	}

	@Override
	public double[][] getMipmapResolutions( final int setup )
	{
		return perSetupMipmapResolutions.get( setup );
	}

	@Override
	public int numMipmapLevels( final int setup )
	{
		return getMipmapResolutions( setup ).length;
	}
}
