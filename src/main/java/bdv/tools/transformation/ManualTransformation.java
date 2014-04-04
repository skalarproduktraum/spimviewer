package bdv.tools.transformation;

import java.util.ArrayList;
import java.util.List;

import net.imglib2.realtransform.AffineTransform3D;

import org.jdom2.Element;

import bdv.viewer.Source;
import bdv.viewer.ViewerPanel;
import bdv.viewer.state.SourceState;
import bdv.viewer.state.ViewerState;

public class ManualTransformation
{
	protected final ViewerPanel viewer;

	protected final XmlIoTransformedSources io;

	public ManualTransformation( final ViewerPanel viewer )
	{
		this.viewer = viewer;
		io = new XmlIoTransformedSources();
	}

	public Element toXml()
	{
		final List< TransformedSource< ? > > sources = getTransformedSources();
		final ArrayList< AffineTransform3D > transforms = new ArrayList< AffineTransform3D >( sources.size() );
		for ( final TransformedSource< ? > s : sources )
		{
			final AffineTransform3D t = new AffineTransform3D();
			s.getFixedTransform( t );
			transforms.add( t );
		}
		return io.toXml( new ManualSourceTransforms( transforms ) );
	}

	public void restoreFromXml( final Element parent )
	{
		final Element elem = parent.getChild( io.getTagName() );
		final List< TransformedSource< ? > > sources = getTransformedSources();
		final List< AffineTransform3D > transforms = io.fromXml( elem ).getTransforms();
		if ( sources.size() != transforms.size() )
			System.err.println( "failed to load <" + io.getTagName() + "> source and transform count mismatch" );
		else
			for ( int i = 0; i < sources.size(); ++i )
				sources.get( i ).setFixedTransform( transforms.get( i ) );
	}

	private ArrayList< TransformedSource< ? > > getTransformedSources()
	{
		final ViewerState state = viewer.getState();
		final ArrayList< TransformedSource< ? > > list = new ArrayList< TransformedSource< ? > >();
		for ( final SourceState< ? > sourceState : state.getSources() )
		{
			final Source< ? > source = sourceState.getSpimSource();
			if ( TransformedSource.class.isInstance( source ) )
				list.add( (TransformedSource< ? > ) source );
		}
		return list;
	}
}
