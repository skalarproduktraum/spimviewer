package bdv.img.cache;

public interface CacheArrayLoader< A >
{
	public int getBytesPerElement();

	public A loadArray( final int timepoint, final int setup, final int level, int[] dimensions, long[] min ) throws InterruptedException;

	public A emptyArray( final int[] dimensions );
}
