package conversions;

import org.entermedia.locks.Lock;
import org.openedit.Data;
import org.openedit.data.Searcher 
import org.openedit.entermedia.modules.*;
import org.openedit.entermedia.edit.*;

import com.openedit.ModuleManager;
import com.openedit.page.*;
import org.openedit.entermedia.*;

import com.openedit.entermedia.scripts.ScriptLogger;
import com.openedit.hittracker.*;
import org.openedit.entermedia.creator.*;

import com.openedit.users.User;
import com.openedit.util.*;

import org.openedit.xml.*;
import conversions.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;



class ConvertRunner implements Runnable
{
	MediaArchive mediaarchive;
	Searcher tasksearcher;
	Searcher presetsearcher;
	Searcher itemsearcher;
	Data hit;
	ScriptLogger log;
	User user;
	ModuleManager moduleManager;
	ConvertResult result = null;
	
	public void run()
	{
		try
		{
			convert();
		}
		catch (Throwable ex )
		{
			log.error(ex);
		}
	}
	public void convert()
	{
		Data realtask = tasksearcher.searchById(hit.getId());
		//log.info("should be ${hit.status} but was ${realtask.status}");
		
		if (realtask != null)
		{
			String presetid = hit.get("presetid");
			//log.debug("starting preset ${presetid}");
			Data preset = presetsearcher.searchById(presetid);
			if(preset != null)
			{
				try
				{
					String sourcepath = hit.get("sourcepath");
					Lock lock = mediaarchive.lockAssetIfPossible(sourcepath, user);
					if( lock == null)
					{
						log.info("asset already being processed ${sourcepath}");
						return;
					}
					
					try
					{
						result = doConversion(mediaarchive, realtask, preset,sourcepath);
					}
					finally
					{
						mediaarchive.releaseLock(lock);
					}
				}
				catch(Throwable e)
				{
					result = new ConvertResult();
					result.setOk(false);
					result.setError(e.toString());
					log.error("Conversion Failed", e);
				}
				
				if(result != null)
				{
					if(result.isOk())
					{
						if(result.isComplete())
						{
							realtask.setProperty("status", "complete");
							String itemid = realtask.get("itemid")
							if(itemid != null)
							{
								//The item should have a pointer to the conversion, not the other way around
								Data item = itemsearcher.searchById(itemid);
								item.setProperty("status", "converted");
								itemsearcher.saveData(item, null);
							}
							realtask.setProperty("externalid", result.get("externalid"));
							Asset asset = mediaarchive.getAssetBySourcePath(hit.get("sourcepath"));
							
							mediaarchive.fireMediaEvent("conversions/conversioncomplete",user,asset);
						}
						else
						{
							realtask.setProperty("status", "submitted");
							realtask.setProperty("externalid", result.get("externalid"));
						}
					}
					else if ( result.isError() )
					{
						realtask.setProperty('status', 'error');
						realtask.setProperty("errordetails", result.getError() );
						
						//TODO: Remove this one day
						String itemid = realtask.get("itemid")
						if(itemid != null)
						{
							Data item = itemsearcher.searchById(itemid);
							item.setProperty("status", "error");
							item.setProperty("errordetails", result.getError() );
							itemsearcher.saveData(item, null);
						}
						//	conversionfailed  conversiontask assetsourcepath, params[id=102], admin
						Map params = new HashMap();
						params.put("taskid",realtask.getId());
						//String operation, String inMetadataType, String inSourcePath, Map inParams, User inUser)
						mediaarchive.fireMediaEvent("conversions/conversionerror","conversiontask", realtask.getSourcePath(), params, user);
						
					}
					else
					{
						String sourcepath = hit.get("sourcepath");
						log.debug("conversion had no error and will try again later for ${sourcepath}");
						return;
					}
					tasksearcher.saveData(realtask, user);
				}
			}
			else
			{
				log.info("Can't run conversion for task '${realtask.getId()}': Invalid presetid ${presetid}");
			}
		}
		else
		{
			log.info("Can't find task object with id '${hit.getId()}'. Index out of date?")
		}
	}
	
	protected ConvertResult doConversion(MediaArchive inArchive, Data inTask, Data inPreset, String inSourcepath)
	{
	String status = inTask.get("status");
	
	String type = inPreset.get("type"); //rhozet, ffmpeg, etc
	MediaCreator creator = getMediaCreator(inArchive, type);
	log.debug("Converting with type: ${type} using ${creator.class} with status: ${status}");
	
	if (creator != null)
	{
		Map props = new HashMap();
		String guid = inPreset.guid;
		if( guid != null)
		{
			Searcher presetdatasearcher = inArchive.getSearcherManager().getSearcher(inArchive.getCatalogId(), "presetdata" );
			Data presetdata = presetdatasearcher.searchById(guid);
			//copy over the preset properties..
			props.put("guid", guid); //needed?
			if( presetdata != null && presetdata.getProperties() != null)
			{
				props.putAll(presetdata.getProperties());
			}
		}
		ConvertInstructions inStructions = creator.createInstructions(props,inArchive,inPreset.get("extension"),inSourcepath);
		
		//inStructions.setOutputExtension(inPreset.get("extension"));
		//log.info( inStructions.getProperty("guid") );
		Asset asset = inArchive.getAssetBySourcePath(inSourcepath);
		if(asset == null)
		{
			return new ConvertResult();
		}
		inStructions.setAssetSourcePath(asset.getSourcePath());
		String extension = PathUtilities.extractPageType(inPreset.get("outputfile") );
		inStructions.setOutputExtension(extension);

		if("new".equals(status) || "retry".equals(status))
		{
			String outputpage = "/WEB-INF/data/${inArchive.catalogId}/generated/${asset.sourcepath}/${inPreset.outputfile}";
			Page output = inArchive.getPageManager().getPage(outputpage);
			log.debug("Running Media type: ${type} on asset ${asset.getSourcePath()}" );
			result = creator.convert(inArchive, asset, output, inStructions);
		}
		else if("submitted".equals(status))
		{
			result = creator.updateStatus(inArchive, inTask, asset, inStructions);
		}
		else
		{
			log.info("${status} status not new or retry, is index out of date? ");
		}
	}
	else
	{
		log.info("Can't find media creator for type '${type}'");
	}
	return result;
  }
//TODO: Cache in map
private MediaCreator getMediaCreator(MediaArchive inArchive, String inType)
{
	MediaCreator creator = moduleManager.getBean(inType + "Creator");

/*	GroovyClassLoader loader = engine.getGroovyClassLoader();
	Class groovyClass = loader.loadClass("conversions.creators.${inType}Creator");
	
	MediaCreator creator = (MediaCreator) groovyClass.newInstance();
	
	creator.setPageManager(mediaarchive.getPageManager());
	creator.setExec(mediaarchive.getModuleManager().getBean("exec"));

	//				<ref bean="ffMpegImageCreator" />
	//			<ref bean="exifToolThumbCreator" />
	if( inType == "imagemagick") //TODO:Use Spring
	{
		MediaCreator child = getMediaCreator(inArchive,"ffmpegimage");
		creator.addPreProcessor(child);
		child = getMediaCreator(inArchive,"exiftoolthumb");
		creator.addPreProcessor(child);

	}
	*/
	return creator;
 }
} //End Runnable methods

protected ConvertRunner createRunnable(MediaArchive mediaarchive, Searcher tasksearcher, Searcher presetsearcher, Searcher itemsearcher, Data hit)
{
	   ConvertRunner runner = new ConvertRunner();
	   runner.mediaarchive = mediaarchive;
	   runner.tasksearcher = tasksearcher;
	   runner.presetsearcher = presetsearcher;
	   runner.itemsearcher = itemsearcher;
	   runner.hit = hit;
	   runner.log = log;
	   runner.user = user;
	   runner.moduleManager= moduleManager;
	   return runner;
}
   
		
public void checkforTasks()
{
	mediaarchive = (MediaArchive)context.getPageValue("mediaarchive");//Search for all files looking for videos
	
	Searcher tasksearcher = mediaarchive.getSearcherManager().getSearcher (mediaarchive.getCatalogId(), "conversiontask");
	Searcher itemsearcher = mediaarchive.getSearcherManager().getSearcher (mediaarchive.getCatalogId(), "orderitem");
	Searcher presetsearcher = mediaarchive.getSearcherManager().getSearcher (mediaarchive.getCatalogId(), "convertpreset");
	
	
	SearchQuery query = tasksearcher.createSearchQuery();
	query.addOrsGroup("status", "new submitted retry");
	query.addSortBy("ordering");
	
	String assetid = context.getRequestParameter("assetid");
	if(assetid != null)
	{
		query.addMatches("assetid", assetid);
	}
	context.setRequestParameter("assetid", (String)null); //so we clear it out for next time. needed?
	HitTracker newtasks = tasksearcher.search(query);

	log.info("processing ${newtasks.size()} conversions");
	
	if( newtasks.size() == 1 )
	{
		ConvertRunner runner = createRunnable(mediaarchive,tasksearcher,presetsearcher, itemsearcher, newtasks.first() );
		runner.run();
	}
	else
	{
		ExecutorManager executorManager = (ExecutorManager)moduleManager.getBean("executorManager");
		ExecutorService  executor = executorManager.createExecutor();
		for(Object hit: newtasks)
		{
			ConvertRunner runner = createRunnable(mediaarchive,tasksearcher,presetsearcher, itemsearcher, hit );
			executor.execute(runner);
		}
		executorManager.waitForIt(executor);
	}
	
	if( newtasks.size() > 0 )
	{
		mediaarchive.fireSharedMediaEvent("conversions/conversionscomplete");
	}
	
}


checkforTasks();

