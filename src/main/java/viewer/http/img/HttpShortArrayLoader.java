package viewer.http.img;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;

import net.imglib2.img.basictypeaccess.array.ShortArray;

public class HttpShortArrayLoader implements HttpArrayLoader< ShortArray >
{
	final String baseUrl;

	public HttpShortArrayLoader( final String baseUrl )
	{
		this.baseUrl = baseUrl;
	}

	@Override
	public ShortArray loadArray( final int index, final int timepoint, final int setup, final int level, final int[] dimensions, final long[] min )
	{
		final ShortArray array = emptyArray( dimensions );
		try
		{
			final URL url = new URL( String.format( "%s?p=cell/%d/%d/%d/%d/%d/%d/%d/%d/%d/%d", baseUrl, index, timepoint, setup, level, dimensions[0], dimensions[1], dimensions[2], min[0], min[1], min[2] ) );
			final InputStream s = url.openStream();

			final short[] data = array.getCurrentStorageArray();
			final byte[] buf = new byte[ 4096 ];
			for ( int i = 0, l = s.read( buf ) / 2; l > 0; l = s.read( buf ) / 2, i += l )
				ByteBuffer.wrap( buf ).asShortBuffer().get( data, i, l );
			s.close();
		}
		catch ( final MalformedURLException e )
		{
			e.printStackTrace();
		}
		catch ( final IOException e )
		{
			e.printStackTrace();
		}
		return array;
	}

	@Override
	public ShortArray emptyArray( final int[] dimensions )
	{
		int numEntities = 1;
		for ( int i = 0; i < dimensions.length; ++i )
			numEntities *= dimensions[ i ];
		return new ShortArray( numEntities );
	}

	@Override
	public int getBytesPerElement() {
		return 2;
	}
}
