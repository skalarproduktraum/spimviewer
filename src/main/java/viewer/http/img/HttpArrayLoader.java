package viewer.http.img;

public interface HttpArrayLoader< A >
{
	public int getBytesPerElement();

	public A loadArray( final int index, final int timepoint, final int setup, final int level, int[] dimensions, long[] min );

	public A emptyArray( final int[] dimensions );
}
