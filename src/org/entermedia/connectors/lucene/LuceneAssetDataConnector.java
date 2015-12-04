package org.entermedia.connectors.lucene;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.taxonomy.TaxonomyWriter;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.Version;
import org.openedit.Data;
import org.openedit.data.CompositeData;
import org.openedit.data.PropertyDetail;
import org.openedit.data.PropertyDetails;
import org.openedit.data.lucene.BaseLuceneSearcher;
import org.openedit.data.lucene.FullTextAnalyzer;
import org.openedit.data.lucene.LuceneIndexer;
import org.openedit.data.lucene.NullAnalyzer;
import org.openedit.data.lucene.RecordLookUpAnalyzer;
import org.openedit.entermedia.Asset;
import org.openedit.entermedia.AssetArchive;
import org.openedit.entermedia.CategoryArchive;
import org.openedit.entermedia.MediaArchive;
import org.openedit.entermedia.search.DataConnector;
import org.openedit.repository.ContentItem;

import com.openedit.ModuleManager;
import com.openedit.OpenEditException;
import com.openedit.hittracker.HitTracker;
import com.openedit.page.Page;
import com.openedit.page.manage.PageManager;
import com.openedit.users.User;
import com.openedit.util.IntCounter;
import com.openedit.util.PathProcessor;

public class LuceneAssetDataConnector extends BaseLuceneSearcher implements DataConnector
{
	static final Log log = LogFactory.getLog(LuceneAssetDataConnector.class);
	protected static final String CATALOGIDX = "catalogid";
	protected static final String CATEGORYID = "categoryid";
	protected DecimalFormat fieldDecimalFormatter;
	protected PageManager fieldPageManager;
	protected ModuleManager fieldModuleManager;
	protected CategoryArchive fieldCategoryArchive;
	protected MediaArchive fieldMediaArchive;
	protected IntCounter fieldIntCounter;
	protected Map fieldAssetPaths;

	public LuceneAssetDataConnector()
	{
		setFireEvents(true);
	}

	public Data createNewData()
	{
		Asset temp = new Asset(getMediaArchive());
		return temp;

	}

	public String nextId()
	{
		String countString = String.valueOf(getIntCounter().incrementCount());
		return countString;
	}

	public void updateIndex(Asset inAsset)
	{
		List all = new ArrayList(1);
		all.add(inAsset);
		updateIndex(all, false);
		clearIndex(); // Does not flush because it will flush if needed
		// anyways on a search

	}

	public Analyzer getAnalyzer()
	{
		if (fieldAnalyzer == null)
		{
			Map analyzermap = new HashMap();
			// analyzermap.put("description", new
			// EnglishAnalyzer(Version.LUCENE_36));
			analyzermap.put("description", new FullTextAnalyzer(Version.LUCENE_41));

			analyzermap.put("foldersourcepath", new NullAnalyzer());

			PropertyDetails details = getPropertyDetails();
			NullAnalyzer nua = new NullAnalyzer();
			//analyzermap.put("id", );
			for (Iterator iterator = details.iterator(); iterator.hasNext();)
			{
				PropertyDetail detail = (PropertyDetail) iterator.next();
				String field = detail.getId();
				if (field.contains("sourcepath") || field.equals("id"))
				{
					analyzermap.put(field, nua);
				}
				else if (detail.isList() && !detail.isMultiValue()) //multi needs to be tokenized
				{
					analyzermap.put(field, nua);
				}
			}

			PerFieldAnalyzerWrapper composite = new PerFieldAnalyzerWrapper(new RecordLookUpAnalyzer(), analyzermap);

			fieldAnalyzer = composite;
		}
		return fieldAnalyzer;
	}

	public LuceneIndexer getLuceneIndexer()
	{
		if (fieldLuceneIndexer == null)
		{
			LuceneAssetIndexer luceneIndexer = new LuceneAssetIndexer();
			luceneIndexer.setAnalyzer(getAnalyzer());
			luceneIndexer.setSearcherManager(getSearcherManager());
			luceneIndexer.setUsesSearchSecurity(true);
			luceneIndexer.setNumberUtils(getNumberUtils());
			luceneIndexer.setRootDirectory(getRootDirectory());
			luceneIndexer.setMediaArchive(getMediaArchive());
			if (getMediaArchive().getAssetSecurityArchive() == null)
			{
				log.error("Asset Security Archive Not Set");
			}
			luceneIndexer.setAssetSecurityArchive(getMediaArchive().getAssetSecurityArchive());
			fieldLuceneIndexer = luceneIndexer;
		}
		return fieldLuceneIndexer;
	}

	public void updateIndex(Collection<Data> inAssets, User inUser)
	{
		if (log.isDebugEnabled())
		{
			log.debug("update index");
		}

		try
		{
			PropertyDetails details = getPropertyDetails();

			for (Iterator iter = inAssets.iterator(); iter.hasNext();)
			{
				Asset asset = (Asset) iter.next();
				IndexWriter writer = getIndexWriter();
				Document doc = getIndexer().populateAsset(writer, asset, false, details);

				doc = getIndexer().updateFacets(details, doc, getTaxonomyWriter(), getFacetConfig());
				getIndexer().writeDoc(writer, asset.getId().toLowerCase(), doc, false);

			}
			// if (inOptimize)
			// {
			// getIndexWriter().optimize();
			// log.info("Optimized");
			// }
			if (inAssets.size() > 100)
			{
				flush();
			}
			else
			{
				clearIndex();
			}
			// else will be flushed next time someone searches. This is a key
			// performance improvement for things like voting that need to be
			// fast
			// BaseLuceneSearcher implements Shutdownable
		}
		catch (Exception ex)
		{
			clearIndex(); // try to recover
			if (ex instanceof OpenEditException)
			{
				throw (OpenEditException) ex;
			}
			throw new OpenEditException(ex);
		}
	}

	protected LuceneAssetIndexer getIndexer()
	{
		return (LuceneAssetIndexer) getLuceneIndexer();
	}

	protected void reIndexAll(final IndexWriter writer,final TaxonomyWriter inTaxonomyWriter)
	{
		// http://www.onjava.com/pub/a/onjava/2003/03/05/lucene.html
		// http://www.onjava.com/pub/a/onjava/2003/03/05/lucene.html?page=2
		// writer.mergeFactor = 10;
		// writer.setMergeFactor(100);
		// writer.setMaxBufferedDocs(2000);
		final PropertyDetails details = getPropertyDetails();
		try
		{
			IndexAllAssets reindexer = new IndexAllAssets()
			{
				public void processFile(ContentItem inContent, User inUser)
				{
					String path = makeSourcePath(inContent); //Does this deal with folder based assets?
					try
					{
						Asset asset = getMediaArchive().getAssetArchive().getAssetBySourcePath(path);
						if (asset != null)
						{
							// This should try to convert the Id into a path. The path will be null if the asset is not in the index.
							if (fieldSourcePaths.contains(asset.getSourcePath())) //Hack to deal with folders with sub assets in it
							{
								return;
							}
							Document doc = getIndexer().populateAsset(writer, asset, false, details);
							doc = getIndexer().updateFacets(details, doc, inTaxonomyWriter, getFacetConfig());
							getIndexer().writeDoc(writer, asset.getId().toLowerCase(), doc, false);

							incrementCount();
							logcount++;
							if (logcount == 1000)
							{
								log.info("Reindex processed " + getExecCount() + " index updates so far");
								logcount = 0;
							}
						}
						else
						{
							log.info("Error loading asset: " + path);
						}
					}
					catch (Throwable ex)
					{
						log.error("Could not read asset: " + path + " continuing " + ex, ex);
					}
				}
			};
			reindexer.setPageManager(getPageManager());
			//reindexer.setIndexer(getIndexer());
			//reindexer.setTaxonomyWriter(inTaxonomyWriter);
			//reindexer.setMediaArchive(getMediaArchive());

			/* Search in the new path, if it exists */
			Page root = getPageManager().getPage("/WEB-INF/data/" + getCatalogId() + "/assets/");
			if (root.exists())
			{
				reindexer.setRootPath(root.getPath());
				reindexer.process();
			}

			/* Search in the old place */
			// reindexer.setRootPath("/" + getCatalogId() + "/assets/");
			// reindexer.process();

			log.info("Reindex completed on with " + reindexer.getExecCount() + " assets");
			// writer.optimize();
			if (inTaxonomyWriter != null)
			{
				inTaxonomyWriter.commit();
			}
			writer.commit();

		}
		catch (Throwable ex)
		{
			log.error(ex);
			throw new OpenEditException(ex);
		}
	}

	public MediaArchive getMediaArchive()
	{
		if (fieldMediaArchive == null)
		{
			fieldMediaArchive = (MediaArchive) getModuleManager().getBean(getCatalogId(), "mediaArchive");
		}

		return fieldMediaArchive;
	}
	public void deleteAll(User inUser)
	{
		PathProcessor processor = new PathProcessor()
		{
			public void processFile(ContentItem inContent, User inUser)
			{
				if (inContent.getPath().endsWith("data.xml"))
				{
					getPageManager().getRepository().remove(inContent);
				}
			}
		};
		processor.setRecursive(true);
		processor.setRootPath("/WEB-INF/data/" + getCatalogId() + "/assets/");
		processor.setPageManager(getPageManager());
		processor.setIncludeExtensions("xml");
		processor.process();
		reIndexAll();
	}
	public void deleteData(Data inData)
	{
		if (inData instanceof Asset)
		{
			getAssetArchive().deleteAsset((Asset) inData);
		}
		else
		{

			Asset asset = (Asset) searchById(inData.getId());
			if (asset != null)
			{
				getAssetArchive().deleteAsset(asset);

			}
		}
	}

	public void deleteFromIndex(Asset inAsset)
	{
		deleteFromIndex(inAsset.getId());
	}

	public void deleteFromIndex(String inId)
	{
		// TODO Auto-generated method stub
		log.info("delete from index " + inId);

		try
		{
			// Query q = getQueryParser().parse("id:" + inId);
			Term term = new Term("id", inId);
			getIndexWriter().deleteDocuments(term);
			// getIndexWriter().deleteDocuments(q);
			clearIndex();
		}
		catch (Exception ex)
		{
			throw new OpenEditException(ex);
		}
	}

	public void deleteFromIndex(HitTracker inOld)
	{
		if (inOld.size() == 0)
		{
			return;
		}
		Term[] all = new Term[inOld.getTotal()];
		for (int i = 0; i < all.length; i++)
		{
			Object hit = (Object) inOld.get(i);
			String id = inOld.getValue(hit, "id");
			Term term = new Term("id", id);
			all[i] = term;
		}
		try
		{
			getIndexWriter().deleteDocuments(all);
		}
		catch (Exception e)
		{
			throw new OpenEditException(e);
		}
		clearIndex();
	}

	public void saveData(Data inData, User inUser)
	{
		if (inData instanceof CompositeData)
		{
			saveCompositeData((CompositeData) inData, inUser);
		}
		else if (inData instanceof Asset)
		{
			Asset asset = (Asset) inData;
			if (asset.getId() == null)
			{
				asset.setId(nextId());
			}
			getAssetArchive().saveAsset(asset);
			getCacheManager().put(getIndexPath(), asset.getId(), asset);
			updateIndex(asset);
		}
	}

	public PageManager getPageManager()
	{
		return fieldPageManager;
	}

	public void setPageManager(PageManager inPageManager)
	{
		fieldPageManager = inPageManager;
	}

	public ModuleManager getModuleManager()
	{
		return getSearcherManager().getModuleManager();
	}

	public void setModuleManager(ModuleManager inModuleManager)
	{
		fieldModuleManager = inModuleManager;
	}

	public CategoryArchive getCategoryArchive()
	{
		if (fieldCategoryArchive == null)
		{
			fieldCategoryArchive = (CategoryArchive) getModuleManager().getBean(getCatalogId(), "categoryArchive");
		}
		return fieldCategoryArchive;
	}

	public void setCategoryArchive(CategoryArchive inCategoryArchive)
	{
		fieldCategoryArchive = inCategoryArchive;
	}

	public AssetArchive getAssetArchive()
	{
		return getMediaArchive().getAssetArchive();
	}

	public void saveAllData(Collection inAll, User inUser)
	{
		// check that all have ids
		for (Object object : inAll)
		{
			Data data = (Data) object;
			if (data.getId() == null)
			{
				data.setId(nextId());
			}
		}
		updateIndex(inAll);
		getAssetArchive().saveAllData(inAll, inUser);
	}

	public IntCounter getIntCounter()
	{
		if (fieldIntCounter == null)
		{
			fieldIntCounter = new IntCounter();
			fieldIntCounter.setLabelName("assetIdCount");
			Page prop = getPageManager().getPage("/WEB-INF/data/" + getMediaArchive().getCatalogHome() + "/assets/idcounter.properties");
			File file = new File(prop.getContentItem().getAbsolutePath());
			fieldIntCounter.setCounterFile(file);
		}
		return fieldIntCounter;
	}

	public void setIntCounter(IntCounter inIntCounter)
	{
		fieldIntCounter = inIntCounter;
	}

	public Object searchByField(String inField, String inValue)
	{
		if (inValue == null)
		{
			return null;
		}
		if (inField.equals("id") || inField.equals("_id"))
		{
			Data data = (Data) getCacheManager().get(getIndexPath(), inValue);
			if (data == null)
			{
				data = (Data) super.searchByField(inField, inValue);
				if (data == null)
				{
					return null;
				}
			}
			if (data != null && !(data instanceof Asset))
			{
				data = getAssetArchive().getAssetBySourcePath(data.getSourcePath());
				if (data != null)
				{
					getCacheManager().put(getIndexPath(), inValue, data);
					getCacheManager().put(getIndexPath(), data.getSourcePath(), data);
				}
			}
			return data;
		}
		else if (inField.equals("sourcepath"))
		{
			Data data = (Data) getCacheManager().get(getIndexPath(), inValue);
			if (data == null || !(data instanceof Asset))
			{
				data = getAssetArchive().getAssetBySourcePath(inValue);
			}
			else
			{
				return data;
			}
			if (data != null)
			{
				getCacheManager().put(getIndexPath(), data.getId(), data);
				getCacheManager().put(getIndexPath(), data.getSourcePath(), data);
			}
			return data;
		}
		return super.searchByField(inField, inValue);
	}

	public Data getDataBySourcePath(String inSourcePath)
	{
		return getMediaArchive().getAssetArchive().getAssetBySourcePath(inSourcePath);
	}

	public Data getDataBySourcePath(String inSourcePath, boolean inAutocreate)
	{

		return getMediaArchive().getAssetArchive().getAssetBySourcePath(inSourcePath, inAutocreate);
	}


	//	@Override
	//	public void saveAllData(Collection<Data> inAll, User inUser)
	//	{
	//		// TODO Auto-generated method stub
	//		
	//	}
	//
	//	@Override
	// public String idToPath(String inAssetId)
	// {
	// String path = (String) getAssetPaths().get(inAssetId);
	// if (path == null && inAssetId != null)
	// {
	// SearchQuery query = createSearchQuery();
	// query.addExact("id", inAssetId);
	//
	// HitTracker hits = search(query);
	// if (hits.size() > 0)
	// {
	// Data hit = hits.get(0);
	// path = hit.getSourcePath();
	// //mem leak? Will this hold the entire DB?
	// getAssetPaths().put(inAssetId, path);
	// }
	// else
	// {
	// log.info("No such asset in index: " + inAssetId);
	// }
	// }
	// return path;
	// }

	// public Map getAssetPaths()
	// {
	// if (fieldAssetPaths == null)
	// {
	// fieldAssetPaths = new HashMap();
	// }
	// return fieldAssetPaths;
	// }

	@Override
	public Data loadData(Data inHit)
	{
		if( inHit instanceof Asset)
		{
			return (Asset)inHit; //this will never happen
		}
		return (Data)searchById(inHit.getId());
	}
	
}
