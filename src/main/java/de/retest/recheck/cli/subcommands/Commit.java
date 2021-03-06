package de.retest.recheck.cli.subcommands;

import static picocli.CommandLine.ExitCode.OK;
import static picocli.CommandLine.ExitCode.SOFTWARE;
import static picocli.CommandLine.ExitCode.USAGE;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.retest.recheck.cli.PreCondition;
import de.retest.recheck.cli.utils.ErrorHandler;
import de.retest.recheck.cli.utils.FilterUtil;
import de.retest.recheck.cli.utils.SystemInUtil;
import de.retest.recheck.cli.utils.TestReportUtil;
import de.retest.recheck.cli.utils.WarningUtil;
import de.retest.recheck.persistence.NoGoldenMasterFoundException;
import de.retest.recheck.persistence.Persistence;
import de.retest.recheck.persistence.PersistenceFactory;
import de.retest.recheck.persistence.xml.util.StdXmlClassesProvider;
import de.retest.recheck.report.TestReport;
import de.retest.recheck.report.TestReportFilter;
import de.retest.recheck.suite.flow.ApplyChangesToStatesFlow;
import de.retest.recheck.suite.flow.CreateChangesetForAllDifferencesFlow;
import de.retest.recheck.ui.descriptors.SutState;
import de.retest.recheck.ui.diff.AttributeDifference;
import de.retest.recheck.ui.review.ReviewResult;
import de.retest.recheck.ui.review.SuiteChangeSet;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExitCodeGenerator;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command( name = "commit", //
		descriptionHeading = "%nDescription:%n", //
		parameterListHeading = "%nParameters:%n", //
		optionListHeading = "%nOptions:%n", // 
		description = "Accept specified differences of given test report." )
public class Commit implements Runnable, IExitCodeGenerator {

	private static final Logger logger = LoggerFactory.getLogger( Commit.class );

	@Option( names = "--help", usageHelp = true, hidden = true )
	private boolean displayHelp;

	@Option( names = "--all", description = "Accept all differences from the given test report." )
	private boolean all;

	@Option( names = "--exclude", description = FilterUtil.EXCLUDE_OPTION_DESCRIPTION )
	private List<String> exclude;

	@Parameters( arity = "1", description = TestReportUtil.TEST_REPORT_PARAMETER_DESCRIPTION )
	private File testReport;

	private int exitCode = OK;

	@Override
	public void run() {
		if ( !PreCondition.isSatisfied() ) {
			exitCode = USAGE;
			return;
		}
		if ( !inputValidation( all, testReport ) ) {
			exitCode = USAGE;
			return;
		}
		try {
			final List<String> invalidFilters = FilterUtil.getInvalidFilters( exclude );
			if ( !invalidFilters.isEmpty() ) {
				FilterUtil.logWarningForInvalidFilters( invalidFilters );
				exitCode = SOFTWARE;
			} else {
				final TestReport report = TestReportUtil.load( testReport );
				final TestReportFilter filter = new TestReportFilter( FilterUtil.getFilterFiles( exclude ) );
				final TestReport filteredTestReport = filter.filter( report );
				if ( !filteredTestReport.containsChanges() ) {
					logger.warn( "The test report has no differences." );
					return;
				}
				TestReportUtil.print( filteredTestReport, testReport );
				final ReviewResult reviewResult = CreateChangesetForAllDifferencesFlow.create( filteredTestReport );
				checkForWarningAndApplyChanges( reviewResult );
			}
		} catch ( final Exception e ) {
			exitCode = SOFTWARE;
			ErrorHandler.handle( e );
		}
	}

	@Override
	public int getExitCode() {
		return exitCode;
	}

	private void checkForWarningAndApplyChanges( final ReviewResult reviewResult ) {
		final boolean containsWarnings = reviewResult.getAllAttributeDifferences().stream() //
				.anyMatch( AttributeDifference::hasElementIdentificationWarning );
		if ( containsWarnings ) {
			WarningUtil.logWarnings( reviewResult );
			askForApplyChanges( reviewResult );
		} else {
			applyChanges( createSutStatePersistence(), reviewResult );
		}
	}

	private void askForApplyChanges( final ReviewResult reviewResult ) {
		logger.info( "Are you sure you want to continue? (y)es or (n)o:" );
		if ( SystemInUtil.yesOrNo() ) {
			applyChanges( createSutStatePersistence(), reviewResult );
		} else {
			logger.info( "No changes are applied!" );
		}
	}

	private boolean inputValidation( final boolean all, final File testReport ) {
		if ( !all ) {
			logger.warn( "Currently only the 'commit --all' command is implemented." );
			logger.warn( "A command to commit specific differences will be implemented shortly." );
			return false;
		}
		if ( testReport == null ) {
			logger.error( "Please specify exactly one test report." );
			return false;
		}
		return true;
	}

	private void applyChanges( final Persistence<SutState> persistence, final ReviewResult reviewResult ) {
		for ( final SuiteChangeSet suiteChangeSet : reviewResult.getSuiteChangeSets() ) {
			try {
				ApplyChangesToStatesFlow.apply( persistence, suiteChangeSet );
			} catch ( final NoGoldenMasterFoundException e ) {
				logger.error(
						"{}\nPlease make sure that the given test report '{}' is within the corresponding project directory.",
						e.getMessage(), testReport.getAbsolutePath() );
			}
		}
	}

	private static Persistence<SutState> createSutStatePersistence() {
		return new PersistenceFactory( new HashSet<>( Arrays.asList( StdXmlClassesProvider.getXmlDataClasses() ) ) )
				.getPersistence();
	}

	boolean isDisplayHelp() {
		return displayHelp;
	}

	boolean isAll() {
		return all;
	}

	File getTestReport() {
		return testReport;
	}
}
