package viewer.http.img;

import java.lang.ref.SoftReference;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;

import viewer.hdf5.img.Hdf5Cell;
import viewer.hdf5.img.Hdf5GlobalCellCache;

public class HttpGlobalCellCache< A > extends Hdf5GlobalCellCache< A >
{
	final protected ConcurrentHashMap< Key, FutureTask< Hdf5Cell< A > > > requestCache = new ConcurrentHashMap< Key, FutureTask< Hdf5Cell< A > > >();

	final protected HttpArrayLoader< A > loader;

	public HttpGlobalCellCache( final HttpArrayLoader< A > loader, final int numTimepoints, final int numSetups, final int maxNumLevels )
	{
		super( null, numTimepoints, numSetups, maxNumLevels );
		this.loader = loader;
	}

	@Override
	public Hdf5Cell< A > loadGlobal( final int[] cellDims, final long[] cellMin, final int timepoint, final int setup, final int level, final int index )
	{
		final Key k = new Key( timepoint, setup, level, index );
		FutureTask< Hdf5Cell< A > > task;
		synchronized( this )
		{
			final SoftReference< Entry > ref = softReferenceCache.get( k );
			if ( ref != null )
			{
				final Entry entry = ref.get();
				if ( entry != null )
					return entry.getData();
			}
			final FutureTask< Hdf5Cell< A > > newtask = new FutureTask< Hdf5Cell< A > >( new Callable< Hdf5Cell< A > >()
			{
				@Override
				public Hdf5Cell< A > call() throws Exception
				{
//					final IoStatistics statistics = getThreadGroupIoStatistics();
//					if ( statistics.timeoutReached() )
//					{
//						statistics.timeoutCallback();
//						return new Hdf5Cell< A >( cellDims, cellMin, loader.emptyArray( cellDims ) );
//					}

//					statistics.stopWatch.start();
					final Hdf5Cell< A > cell = new Hdf5Cell< A >( cellDims, cellMin, loader.loadArray( index, timepoint, setup, level, cellDims, cellMin ) );
					softReferenceCache.put( k, new SoftReference< Entry >( new Entry( k, cell ) ) );
//					statistics.stopWatch.stop();

					int c = loader.getBytesPerElement();
					for ( final int l : cellDims )
						c *= l;
//					statistics.ioBytes += c;

					requestCache.remove( k );
					return cell;
				}
			} );
			task = requestCache.putIfAbsent( k, newtask );
			if ( task == null )
				task = newtask;
		}
		try
		{
			task.run();
			return task.get();
		}
		catch ( final Exception e )
		{
			e.printStackTrace();
			return null;
		}
	}
}
