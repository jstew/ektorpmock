package org.ektorpmock.impl

import org.ektorp.Options
import org.ektorp.UpdateConflictException
import org.ektorp.DocumentNotFoundException
import org.apache.commons.io.IOUtils
import org.ektorp.support.CouchDbDocument
import org.junit.Test
import org.ektorp.impl.StdObjectMapperFactory
import org.ektorp.CouchDbConnector
import org.junit.Ignore
import org.ektorp.AttachmentInputStream
import org.ektorp.support.TypeDiscriminator
import org.ektorp.support.DesignDocument
import org.ektorp.ViewQuery
import org.ektorp.support.StdDesignDocumentFactory
import org.ektorp.impl.NameConventions
import org.ektorp.ViewResult
import org.ektorp.Attachment
import com.fasterxml.jackson.databind.JsonNode
import org.ektorp.DbAccessException
import org.ektorp.impl.StreamingJsonSerializer
import org.ektorp.BulkDeleteDocument
import org.ektorp.DbInfo

@Ignore
class CouchDbConnectorTestBase {

    protected CouchDbConnector db
    protected String staticId = "SomeId"

    @Test
    void "create with given id"() {
        assert !db.contains(staticId)

        def testDoc = new TestDoc(name: "Jason", age: 31)
        db.create(staticId, testDoc)

        testDoc = db.get(TestDoc, staticId)
        assert testDoc instanceof TestDoc
        assert "Jason" == testDoc.name
        assert 31 == testDoc.age
    }

    @Test
    void "create creates revisions"() {
        def newDoc = createTestDoc()

        def savedDoc = db.get(TestDoc, newDoc.id, new Options().includeRevisions())
        assert savedDoc.revisions
        assert 1 == savedDoc.revisions.ids.size
    }

    @Test
    void "create creates revision list"() {
        def newDoc = createTestDoc()

        def savedDoc = db.get(TestDoc, newDoc.id, new Options().revision(newDoc.revision))
        assert "Jason" == savedDoc.name
        assert 31 == savedDoc.age
        assert newDoc.id == savedDoc.id
        assert newDoc.revision == savedDoc.revision
    }

    @Test()
    void "create same document"() {
//        This is apparently legal
        def testDoc = createTestDoc()

        db.create(testDoc.id, testDoc)
    }

    @Test(expected=UpdateConflictException)
    void "create causes DocumentConflictException"() {
        def testDoc = createTestDoc()

        db.create(testDoc.id, new TestDoc())
    }

    @Test()
    void "create existing document updates document"() {
        def testDoc = createTestDoc()
        def revision = testDoc.revision

        db.create(testDoc.id, testDoc)

        def savedDoc = db.get(TestDoc, testDoc.id)
        def savedRevision = savedDoc.revision

        assert revisionToInt(revision) + 1 == revisionToInt(savedRevision)
    }

    @Test
    void "create and generate id"() {
        def testDoc = new TestDoc(name: "Jason", age: 31)
        assert !testDoc.id

        db.create(testDoc)

        assert testDoc.id
        assert testDoc.revision

        testDoc = db.get(TestDoc, testDoc.id)
        assert testDoc instanceof TestDoc
        assert "Jason" == testDoc.name
        assert 31 == testDoc.age
    }

    @Test(expected=NullPointerException)
    void "create null object"() {
        db.create(null)
    }

    @Test(expected=IllegalArgumentException)
    void "create object not new"() {
        def testDoc = createTestDoc()
        db.create(testDoc)
    }

    @Test
    void "test create updates dbInfo"() {
        DbInfo originalDbInfo = db.getDbInfo()
        def originalDocCount = originalDbInfo.docCount
        def originalUpdateSeq = originalDbInfo.updateSeq

        createTestDoc()

        DbInfo dbInfo = db.getDbInfo()
        def docCount = dbInfo.docCount
        def updateSeq = dbInfo.updateSeq

        assert originalDocCount + 1 == docCount
        assert originalUpdateSeq + 1 == updateSeq
    }

    @Test
    void "update updates document"() {
        def testDoc = createTestDoc()
        assert 31 == testDoc.age
        testDoc.age = 32

        db.update(testDoc)

        assert 32 == db.get(TestDoc, testDoc.id).age
    }

    @Test
    void "update creates document given id"() {
        def testDoc = new TestDoc(id: staticId, name: "Jason", age: 31)
        assert !testDoc.revision

        db.update(testDoc)

        assert testDoc.id
        assert testDoc.revision
    }

    @Test(expected = IllegalArgumentException)
    void "update empty id"() {
        def testDoc = new TestDoc(name: "Jason", age: 31)
        assert !testDoc.id
        assert !testDoc.revision

        db.update(testDoc)

        assert testDoc.id
        assert testDoc.revision
    }

    @Test
    void "update updates revision"() {
        def testDoc = createTestDoc()
        def revision = testDoc.revision
        assert 31 == testDoc.age

        db.update(testDoc)

        assert revisionToInt(revision) + 1 == revisionToInt(db.get(TestDoc, testDoc.id).revision)
    }

    @Test
    void "update appends revision list"() {
        def newDoc = createTestDoc()


        def savedDoc = db.get(TestDoc, newDoc.id)
        assert 31 == savedDoc.age
        savedDoc.age = 32
        db.update(savedDoc)

        savedDoc = db.get(TestDoc, newDoc.id, new Options().revision(newDoc.revision))
        assert "Jason" == savedDoc.name
        assert 31 == savedDoc.age
        assert newDoc.id == savedDoc.id
        assert newDoc.revision == savedDoc.revision
    }

    @Test
    void "update appends revisions"() {
        def newDoc = createTestDoc()

        def savedDoc = db.get(TestDoc, newDoc.id, new Options().includeRevisions())
        assert savedDoc.revisions
        assert 1 == savedDoc.revisions.ids.size

        db.update(savedDoc)

        savedDoc = db.get(TestDoc, newDoc.id, new Options().includeRevisions())
        assert savedDoc.revisions
        assert 2 == savedDoc.revisions.ids.size
    }

    @Test(expected=UpdateConflictException)
    void "update UpdateConflictException"() {
        def staleTestDoc = createTestDoc()
        def id = staleTestDoc.id
        def staleRevision = staleTestDoc.revision

        def testDoc = db.get(TestDoc, id)
        db.update(testDoc)
        assert revisionToInt(staleRevision) < revisionToInt(testDoc.revision)

        db.update(staleTestDoc)
    }

    @Test
    void "test update delete"() {
        def testDoc = createTestDoc()
        assert testDoc.id
        def id = testDoc.id

        def persistedDoc = db.get(TestDoc, id)
        def bulkDeleteDoc = BulkDeleteDocument.of(persistedDoc)

        db.update(bulkDeleteDoc)

        assert !db.contains(id)
    }

    @Test
    void "test update delete bad id"() {
        def testDoc = createTestDoc()

        def bulkDeleteDoc = new BulkDeleteDocument("badId", testDoc.revision)

        assert !db.contains("badId")
//      couchdb does not seem to return an error in this scenario.
        db.update(bulkDeleteDoc)
    }

    @Test
    void "test update updates updateSeq"() {
        def testDoc = createTestDoc()
        DbInfo originalDbInfo = db.getDbInfo()
        def originalUpdateSeq = originalDbInfo.updateSeq

        db.update(testDoc)

        DbInfo dbInfo = db.getDbInfo()
        def updateSeq = dbInfo.updateSeq

        assert originalUpdateSeq + 1 == updateSeq
    }

    @Test
    void "update stream updates document"() {
        def jsonSerialiazer = new StreamingJsonSerializer(new StdObjectMapperFactory().createObjectMapper())
        def testDoc = createTestDoc()
        assert 31 == testDoc.age
        testDoc.age = 32

        def json = jsonSerialiazer.toJson(testDoc)
        def stream = IOUtils.toInputStream(json)
        db.update(testDoc.id, stream, stream.available().toLong(), new Options())

        assert 32 == db.get(TestDoc, testDoc.id).age
    }

    @Test
    void "update stream creates document given id"() {
        def jsonSerialiazer = new StreamingJsonSerializer(new StdObjectMapperFactory().createObjectMapper())
        def testDoc = new TestDoc(id: staticId, name: "Jason", age: 31)
        assert !testDoc.revision

        def json = jsonSerialiazer.toJson(testDoc)
        def stream = IOUtils.toInputStream(json)
        db.update(testDoc.id, stream, stream.available().toLong(), new Options())

        def newDoc = db.get(TestDoc, staticId)
        assert newDoc.id
        assert newDoc.revision
    }

    @Test(expected = IllegalArgumentException)
    void "update stream empty id"() {
        def jsonSerialiazer = new StreamingJsonSerializer(new StdObjectMapperFactory().createObjectMapper())
        def testDoc = new TestDoc(name: "Jason", age: 31)
        assert !testDoc.id
        assert !testDoc.revision

        def json = jsonSerialiazer.toJson(testDoc)
        def stream = IOUtils.toInputStream(json)
        db.update(testDoc.id, stream, stream.available().toLong(), new Options())
    }

    @Test
    void "update stream updates revision"() {
        def jsonSerialiazer = new StreamingJsonSerializer(new StdObjectMapperFactory().createObjectMapper())
        def testDoc = createTestDoc()
        def revision = testDoc.revision
        assert 31 == testDoc.age

        def json = jsonSerialiazer.toJson(testDoc)
        def stream = IOUtils.toInputStream(json)
        db.update(testDoc.id, stream, stream.available().toLong(), new Options())


        assert revisionToInt(revision) + 1 == revisionToInt(db.get(TestDoc, testDoc.id).revision)
    }

    @Test
    void "update stream appends revision list"() {
        def jsonSerialiazer = new StreamingJsonSerializer(new StdObjectMapperFactory().createObjectMapper())
        def newDoc = createTestDoc()

        def savedDoc = db.get(TestDoc, newDoc.id)
        assert 31 == savedDoc.age
        savedDoc.age = 32
        def json = jsonSerialiazer.toJson(savedDoc)
        def stream = IOUtils.toInputStream(json)
        db.update(savedDoc.id, stream, stream.available().toLong(), new Options())

        savedDoc = db.get(TestDoc, newDoc.id, new Options().revision(newDoc.revision))
        assert "Jason" == savedDoc.name
        assert 31 == savedDoc.age
        assert newDoc.id == savedDoc.id
        assert newDoc.revision == savedDoc.revision
    }

    @Test
    void "update stream appends revisions"() {
        def jsonSerialiazer = new StreamingJsonSerializer(new StdObjectMapperFactory().createObjectMapper())
        def newDoc = createTestDoc()

        def savedDoc = db.get(TestDoc, newDoc.id, new Options().includeRevisions())
        assert savedDoc.revisions
        assert 1 == savedDoc.revisions.ids.size

        def json = jsonSerialiazer.toJson(savedDoc)
        def stream = IOUtils.toInputStream(json)
        db.update(savedDoc.id, stream, stream.available().toLong(), new Options())

        savedDoc = db.get(TestDoc, newDoc.id, new Options().includeRevisions())
        assert savedDoc.revisions
        assert 2 == savedDoc.revisions.ids.size
    }



    @Test(expected=UpdateConflictException)
    void "update stream UpdateConflictException"() {
        def jsonSerialiazer = new StreamingJsonSerializer(new StdObjectMapperFactory().createObjectMapper())
        def staleTestDoc = createTestDoc()
        def id = staleTestDoc.id
        def staleRevision = staleTestDoc.revision

        def testDoc = db.get(TestDoc, id)
        db.update(testDoc)
        assert revisionToInt(staleRevision) < revisionToInt(testDoc.revision)


        def json = jsonSerialiazer.toJson(staleTestDoc)
        def stream = IOUtils.toInputStream(json)
        db.update(staleTestDoc.id, stream, stream.available().toLong(), new Options())
    }

    @Test
    void "delete object"() {
        def testDoc = createTestDoc()
        def revision = testDoc.revision

        def deletedRevision = db.delete(testDoc)

        assert revisionToInt(revision) + 1 == revisionToInt(deletedRevision)
        assert !db.contains(testDoc.id)
    }

    @Test(expected=NullPointerException)
    void "delete object null object"() {
        db.delete(null)
    }

    @Test(expected=UpdateConflictException)
    void "delete object UpdateConflictException"() {
        def staleTestDoc = createTestDoc()
        def id = staleTestDoc.id
        def staleRevision = staleTestDoc.revision

        def testDoc = db.get(TestDoc, id)
        db.update(testDoc)
        assert revisionToInt(staleRevision) < revisionToInt(testDoc.revision)

        db.delete(staleTestDoc)
    }

    @Test
    void "delete object UpdateConflictException does not delete"() {
        def staleTestDoc = createTestDoc()
        def id = staleTestDoc.id
        def staleRevision = staleTestDoc.revision

        def testDoc = db.get(TestDoc, id)
        db.update(testDoc)
        assert revisionToInt(staleRevision) < revisionToInt(testDoc.revision)

        try {
            db.delete(staleTestDoc)
            assert false
        } catch (UpdateConflictException uce) {}

        assert db.contains(staleTestDoc.id)
    }

    @Test(expected=DocumentNotFoundException)
    void "test delete bad id"() {
        def testDoc = createTestDoc()

        def result = db.delete("badId", testDoc.revision)
    }

    @Test
    void "delete id and revision"() {
        def testDoc = createTestDoc()
        def id = testDoc.id
        def revision = testDoc.revision

        def deletedRevision = db.delete(id, revision)

        assert revisionToInt(revision) + 1 == revisionToInt(deletedRevision)
        assert !db.contains(testDoc.id)
    }

    @Test(expected=IllegalArgumentException)
    void "delete id and revision no id"() {
        db.delete("", "some revision")
    }

    @Test(expected=UpdateConflictException)
    void "delete id and revision UpdateConflictException"() {
        def staleTestDoc = createTestDoc()
        def id = staleTestDoc.id
        def staleRevision = staleTestDoc.revision
        def staleRevisionInt =revisionToInt(staleRevision)

        def testDoc = db.get(TestDoc, id)
        db.update(testDoc)
        assert revisionToInt(staleRevision) < revisionToInt(testDoc.revision)

        db.delete(id, staleRevision.replaceFirst("$staleRevisionInt", "0"))
    }

    @Test
    void "test delete updates dbInfo"() {
        def testDoc = createTestDoc()
        DbInfo originalDbInfo = db.getDbInfo()
        def originalUpdateSeq = originalDbInfo.updateSeq
        def originalDocDelCount = originalDbInfo.docDelCount

        db.delete(testDoc)

        DbInfo dbInfo = db.getDbInfo()
        def updateSeq = dbInfo.updateSeq
        def docDelCount = dbInfo.docDelCount

        assert originalUpdateSeq + 1 == updateSeq
        assert originalDocDelCount + 1 == docDelCount
    }

    @Test
    void "test copy"() {
        def testDoc = createTestDoc()
        assert testDoc.id
        try {
            db.getAsStream("newId")
            assert false
        } catch (DbAccessException dbae) {}

        String revision = db.copy(testDoc.id, "newId")

        def newTestDoc = db.get(TestDoc, "newId")
        assert revision == newTestDoc.revision
//        This behavior seems odd, but it's what a real couchdb does
        assert testDoc.revision == newTestDoc.revision
        assert "newId" == newTestDoc.id
        assert testDoc.name == newTestDoc.name
        assert testDoc.age == newTestDoc.age
    }

    @Test(expected=UpdateConflictException)
    void "test copy new id exists"() {
        def testDoc = createTestDoc()
        assert testDoc.id

        String revision = db.copy(testDoc.id, testDoc.id)
    }

    @Test(expected=DbAccessException)
    void "test copy old id does not exist"() {
        def testDoc = createTestDoc()
        assert testDoc.id

        String revision = db.copy("badId", "newId")
    }

    @Test
    void "test copy target revision"() {
        TestDoc testDoc1 = createTestDoc()
        TestDoc testDoc2 = new TestDoc(name: "Evan", age: 30)
        db.create(testDoc2)

        String revision = db.copy(testDoc1.id, testDoc2.id, testDoc2.revision)

        def copiedTestDoc = db.get(TestDoc, testDoc2.id)

        assert revision == copiedTestDoc.revision
        assert testDoc2.revision != copiedTestDoc.revision
        assert testDoc2.revision != copiedTestDoc.revision
        assert testDoc2.id == copiedTestDoc.id
        assert testDoc1.name == copiedTestDoc.name
        assert testDoc1.age == copiedTestDoc.age
    }

    @Test(expected=UpdateConflictException)
    void "test copy target revision old"() {
        TestDoc testDoc1 = createTestDoc()
        TestDoc testDoc2 = new TestDoc(name: "Evan", age: 30)
        db.create(testDoc2)
        def revision1 = testDoc2.revision
        db.update(testDoc2)
        def revision2 = testDoc2.revision
        assert revision1 != revision2

        String revision = db.copy(testDoc1.id, testDoc2.id, revision1)
    }

    @Test
    void "get"() {
        def newDoc = createTestDoc()

        def savedDoc = db.get(TestDoc, newDoc.id)
        assert savedDoc instanceof TestDoc
        assert "Jason" == savedDoc.name
        assert 31 == savedDoc.age
    }

    @Test(expected=DocumentNotFoundException)
    void "get DocumentNotFoundException"() {
        assert !db.contains(staticId)

        db.get(TestDoc, staticId)
    }

    @Test
    void "get revisions"() {
        def newDoc = new TestDoc(name: "Jason", age: 31)
        assert !newDoc.id

        db.create(newDoc)

        def savedDoc = db.get(TestDoc, newDoc.id)
        assert !savedDoc.revisions

        savedDoc = db.get(TestDoc, newDoc.id, new Options().includeRevisions())
        assert savedDoc.revisions
        assert 1 == savedDoc.revisions.ids.size
    }

    @Test(expected=DocumentNotFoundException)
    void "get revision not found"() {
        def newDoc = createTestDoc()
        def revision = newDoc.revision
        def revisionInt = revisionToInt(revision)

        def savedDoc = db.get(TestDoc, newDoc.id, new Options().revision(newDoc.revision))
        assert savedDoc

        db.get(TestDoc, newDoc.id, new Options().revision(revision.replace("$revisionInt", "0")))
    }

    @Test
    void "test revision limit setter and getter"() {
        int initRevLimit = db.getRevisionLimit()
        db.setRevisionLimit(initRevLimit + 1)
        assert (initRevLimit + 1) == db.getRevisionLimit()
    }

    @Test
    void "contains"() {
        assert !db.contains(staticId)

        def testDoc = new TestDoc(name: "Jason", age: 31)
        db.create(staticId, testDoc)

        assert db.contains(staticId)
    }

    @Test
    void "getAsStream"() {
        def newDoc = createTestDoc()

        InputStream is = db.getAsStream(newDoc.id)

        String s = IOUtils.toString(is, "UTF-8");
        is.close()

        def objectMapper = new StdObjectMapperFactory().createObjectMapper()
        def deserializedTestDoc = objectMapper.readValue(s, TestDoc)

        assert newDoc.id == deserializedTestDoc.id
        assert newDoc.revision == deserializedTestDoc.revision
        assert newDoc.name == deserializedTestDoc.name
        assert newDoc.age == deserializedTestDoc.age
    }

    @Test(expected=DocumentNotFoundException)
    void "getAsStream DocumentNotFoundException"() {
        assert !db.contains(staticId)

        db.getAsStream(staticId)
    }

    @Test
    void "createAttachment"() {
        def testDoc = createTestDoc()
        def attachmentId = "${testDoc.id}-attachment"
        def data = "Attachment Data"

        db.createAttachment(testDoc.id, testDoc.revision, new AttachmentInputStream(attachmentId, IOUtils.toInputStream(data), "UTF-8"))

        AttachmentInputStream ais = db.getAttachment(testDoc.id, attachmentId)

        assert data == IOUtils.toString(ais)
        assert attachmentId == ais.id
    }

    @Test
    void "createAttachment updates revision"() {
        def testDoc = createTestDoc()
        def revision = testDoc.revision
        def attachmentId = "${testDoc.id}-attachment"
        def data = "Attachment Data"

        db.createAttachment(testDoc.id, testDoc.revision, new AttachmentInputStream(attachmentId, IOUtils.toInputStream(data), "UTF-8"))

        def updatedDoc = db.get(TestDoc, testDoc.id)

        assert revision != updatedDoc.revision
    }

    @Test
    void "createAttachment and document"() {
        def attachmentId = "${staticId}-attachment"
        def data = "Attachment Data"

        db.createAttachment(staticId, new AttachmentInputStream(attachmentId, IOUtils.toInputStream(data), "UTF-8"))

        AttachmentInputStream ais = db.getAttachment(staticId, attachmentId)

        assert data == IOUtils.toString(ais)
    }


    @Test
    void "attach to document"() {
        def testDoc = new TestDoc()
        testDoc.addInlineAttachment(new Attachment("attachmentId", "data".bytes.encodeBase64().toString(), "text"))
        db.create(testDoc)
        assert testDoc.id

        AttachmentInputStream ais = db.getAttachment(testDoc.id, "attachmentId")
        assert "data" == IOUtils.toString(ais)
    }

    @Test
    void "get return attachments"() {
        def testDoc = createTestDoc()
        def attachmentId = "${testDoc.id}-attachment"
        def data = "Attachment Data"

        assert !db.get(TestDoc, testDoc.id).attachments

        db.createAttachment(testDoc.id, testDoc.revision, new AttachmentInputStream(attachmentId, IOUtils.toInputStream(data), "UTF-8"))

        assert db.get(TestDoc, testDoc.id).attachments
    }

    @Test(expected=UpdateConflictException)
    void "createAttachment and document already exists"() {
        def testDoc = createTestDoc()
        def attachmentId = "${testDoc.id}-attachment"
        def data = "Attachment Data"

        db.createAttachment(testDoc.id, new AttachmentInputStream(attachmentId, IOUtils.toInputStream(data), "UTF-8"))
    }

    @Test(expected=UpdateConflictException)
    void "createAttachment UpdateConflictException"() {
        def testDoc = createTestDoc()
        def attachmentId = "${testDoc.id}-attachment"
        def data = "Attachment Data"

        db.createAttachment(testDoc.id, testDoc.revision, new AttachmentInputStream(attachmentId, IOUtils.toInputStream(data), "UTF-8"))

        AttachmentInputStream ais = db.getAttachment(testDoc.id, attachmentId)
        assert data == IOUtils.toString(ais)

        db.createAttachment(testDoc.id, testDoc.revision, new AttachmentInputStream(attachmentId, IOUtils.toInputStream(data), "UTF-8"))
    }

    @Test
    void "createAttachment returns document revision"() {
        def testDoc = createTestDoc()
        def attachmentId = "${testDoc.id}-attachment"
        def data = "Attachment Data"

        def attachmentRev = db.createAttachment(testDoc.id, testDoc.revision, new AttachmentInputStream(attachmentId, IOUtils.toInputStream(data), "UTF-8"))
        testDoc = db.get(TestDoc, testDoc.id)

        assert attachmentRev == testDoc.revision
    }

    @Test
    void "test QueryView"() {
        def designDoc = createTestDocDesignDocument()

        createTestDoc()
        db.create(new TestBook(title: "Hop On Pop", author: "Dr. Seuss"))

        List<TestDoc> testDocs = db.queryView(createQuery(designDoc.id, "all").includeDocs(true), TestDoc)
        assert testDocs
        testDocs.each {
            assert "TestDoc" == it.type
            assert it instanceof TestDoc

        }
    }

    @Test
    void "test QueryView key"() {
        def designDoc = createTestDocDesignDocument()

        createTestDoc()
        createTestDoc()
        db.create(new TestDoc(name: "Evan", age: 30))
        db.create(new TestDoc(name: "Chris", age: 25))

        List<TestDoc> testDocs = db.queryView(createQuery(designDoc.id, "byName").includeDocs(true).key("Jason"), TestDoc)
        assert testDocs
        testDocs.each {
            assert "TestDoc" == it.type
            assert it instanceof TestDoc
            assert "Jason" == it.name
        }
    }

    @Test
    void "test QueryView key no results"() {
        def designDoc = createTestDocDesignDocument()

        createTestDoc()
        createTestDoc()
        db.create(new TestDoc(name: "Evan", age: 30))
        db.create(new TestDoc(name: "Chris", age: 25))

        List<TestDoc> testDocs = db.queryView(createQuery(designDoc.id, "byName").includeDocs(true).key("Liz"), TestDoc)
        assert !testDocs
    }

    @Test
    void "test QueryView with reduce group"() {
        def designDoc = createTestDocDesignDocument()

        createTestDoc()
        db.create(new TestBook(title: "Hop On Pop", author: "Dr. Seuss"))
        db.create(new TestDoc(name: "test QueryView with reduce", age: 32))
        db.create(new TestDoc(name: "test QueryView with reduce", age: 33))

        ViewResult viewResult= db.queryView(createQuery(designDoc.id, "totalAgeByName").group(true))
        ViewResult.Row row = viewResult.rows.find {
            it.key == "test QueryView with reduce"
        }
        assert 65 == row.valueAsInt
    }

    @Test
    void "test QueryView with reduce not group"() {
        def designDoc = createTestDocDesignDocument()

        createTestDoc()
        db.create(new TestBook(title: "Hop On Pop", author: "Dr. Seuss"))
        db.create(new TestDoc(name: "test QueryView with reduce not group", age: 32))
        db.create(new TestDoc(name: "test QueryView with reduce not group", age: 33))

        ViewResult viewResult= db.queryView(createQuery(designDoc.id, "totalAgeByName").group(false))
        assert 1 == viewResult.rows.size()
        def total = viewResult.rows.sum { ViewResult.Row row ->
            row.valueAsInt
        }

        viewResult= db.queryView(createQuery(designDoc.id, "totalAgeByName"))
        assert 1 == viewResult.rows.size()
        assert total == viewResult.rows[0].valueAsInt
    }

    @Test
    void "test QueryView with builtin reduce sum"() {
        def designDoc = createTestDocDesignDocument()

        createTestDoc()
        db.create(new TestBook(title: "Hop On Pop", author: "Dr. Seuss"))
        db.create(new TestDoc(name: "test QueryView with builtin reduce sum", age: 32))
        db.create(new TestDoc(name: "test QueryView with builtin reduce sum", age: 33))

        ViewResult viewResult= db.queryView(createQuery(designDoc.id, "builtInTotalAgeByNameView").group(true))
        ViewResult.Row row = viewResult.rows.find {
            it.key == "test QueryView with builtin reduce sum"
        }
        assert 65 == row.valueAsInt
    }

    @Test
    void "test QueryView with builtin reduce count"() {
        def designDoc = createTestDocDesignDocument()

        createTestDoc()
        db.create(new TestBook(title: "Hop On Pop", author: "Dr. Seuss"))
        db.create(new TestDoc(name: "test QueryView with builtin reduce count", age: 32))
        db.create(new TestDoc(name: "test QueryView with builtin reduce count", age: 33))

        ViewResult viewResult= db.queryView(createQuery(designDoc.id, "builtInCountView").group(true))
        ViewResult.Row row = viewResult.rows.find {
            it.key == "test QueryView with builtin reduce count"
        }
        assert 2 == row.valueAsInt
    }

    @Test
    void "test QueryView with builtin reduce stats"() {
        def designDoc = createTestDocDesignDocument()

        createTestDoc()
        db.create(new TestBook(title: "Hop On Pop", author: "Dr. Seuss"))
        db.create(new TestDoc(name: "test QueryView with builtin reduce stats", age: 1))
        db.create(new TestDoc(name: "test QueryView with builtin reduce stats", age: 2))
        db.create(new TestDoc(name: "test QueryView with builtin reduce stats", age: 3))

        ViewResult viewResult= db.queryView(createQuery(designDoc.id, "builtInStatsView").group(true))
        ViewResult.Row row = viewResult.rows.find {
            it.key == "test QueryView with builtin reduce stats"
        }
        JsonNode node = row.valueAsNode
        node.with {
            assert 1 == it.get("min").asInt()
            assert 3 == it.get("max").asInt()
            assert 3 == it.get("count").asInt()
            assert 6 == it.get("sum").asInt()
            assert 14 == it.get("sumsqr").asInt()
        }
    }

    @Test
    void "test QueryView docs not included"() {
        def designDoc = createTestDocDesignDocument()

        createTestDoc()
        db.create(new TestBook(title: "Hop On Pop", author: "Dr. Seuss"))

        ViewResult vr = db.queryView(createQuery(designDoc.id, "all").includeDocs(false))

        vr.iterator().each { ViewResult.Row r ->
            assert r.id
            assert !r.doc
        }
    }

    @Test(expected=DbAccessException)
    void "test QueryView docs not included class given"() {
        def designDoc = createTestDocDesignDocument()

        createTestDoc()
        db.create(new TestBook(title: "Hop On Pop", author: "Dr. Seuss"))

        List<TestDoc> testDocs = db.queryView(createQuery(designDoc.id, "all").includeDocs(false), TestDoc)
    }

    @Test(expected = DocumentNotFoundException)
    void "test QueryView no design document"() {
        ViewResult viewResult = db.queryView(createQuery("not a design doc", "all"))
    }

    @Test(expected = DocumentNotFoundException)
    void "test QueryView no view"() {
        def designDoc = createTestDocDesignDocument()
        ViewResult viewResult = db.queryView(createQuery(designDoc.id, "notAView"))
    }

    @Test
    void "test QueryView do not include docs"() {
        def designDoc = createTestDocDesignDocument()

        createTestDoc()
        db.create(new TestBook(title: "Hop On Pop", author: "Dr. Seuss"))

        ViewResult viewResult = db.queryView(createQuery(designDoc.id, "all"))

        viewResult.rows.each {
            assert it.key
            assert !it.doc
        }
    }

    @Test
    void "test QueryView set max"() {
        def designDoc = createTestDocDesignDocument()
        createTestDoc()
        createTestDoc()

        ViewResult viewResult = db.queryView(createQuery(designDoc.id, "all"))
        def totalResults = viewResult.totalRows
        assert 2 <= totalResults

        viewResult = db.queryView(createQuery(designDoc.id, "all").limit(totalResults - 1))
        assert (totalResults - 1) == viewResult.rows.size()
    }

    @Test
    void "test QueryView set max greater than total results"() {
        def designDoc = createTestDocDesignDocument()
        createTestDoc()
        createTestDoc()

        ViewResult viewResult = db.queryView(createQuery(designDoc.id, "all"))
        def totalResults = viewResult.totalRows
        assert 2 <= totalResults

        viewResult = db.queryView(createQuery(designDoc.id, "all").limit(totalResults + 1))
        assert totalResults == viewResult.rows.size()
    }

//    @Test
//    void "test QueryForStream"() {
//        def designDoc = createTestDocDesignDocument()
//        createTestDoc()
//        createTestDoc()
//
//        InputStream inputStream = db.queryForStream(createQuery(designDoc.id, "all"))
//
//        println IOUtils.toString(inputStream)
//        inputStream.close()
//    }

    @Test
    void "test QueryView set skip"() {
        def designDoc = createTestDocDesignDocument()
        createTestDoc()
        createTestDoc()

        ViewResult viewResult = db.queryView(createQuery(designDoc.id, "all"))
        def totalResults = viewResult.totalRows
        assert 2 <= totalResults
        assert 0 == viewResult.offset

        viewResult = db.queryView(createQuery(designDoc.id, "all").skip(totalResults - 1))

        assert (totalResults - 1) == viewResult.offset
        assert 1 == viewResult.rows.size()
    }

    @Test
    void "test QueryView set skip greater than total results"() {
        def designDoc = createTestDocDesignDocument()
        createTestDoc()
        createTestDoc()

        ViewResult viewResult = db.queryView(createQuery(designDoc.id, "all"))
        def totalResults = viewResult.totalRows
        assert 2 <= totalResults
        assert 0 == viewResult.offset

        viewResult = db.queryView(createQuery(designDoc.id, "all").skip(totalResults + 1))

        assert totalResults == viewResult.offset
        assert 0 == viewResult.rows.size()
    }

    @Test
    void "test addToBulkBuffer"() {
        assert !db.bulkBufferManager.getCurrentBuffer()
        def doc = new TestDoc()
        db.addToBulkBuffer(doc)

        assert 1 == db.bulkBufferManager.getCurrentBuffer().size()
        assert doc == db.bulkBufferManager.getCurrentBuffer()[0]
    }

    @Test
    void "test clearBulkBuffer"() {
        assert !db.bulkBufferManager.getCurrentBuffer()
        def doc = new TestDoc()
        db.addToBulkBuffer(doc)

        assert 1 == db.bulkBufferManager.getCurrentBuffer().size()
        assert doc == db.bulkBufferManager.getCurrentBuffer()[0]

        db.clearBulkBuffer()

        assert !db.bulkBufferManager.getCurrentBuffer()
    }

    @Test
    void "test flushBulkBuffer flushes buffer"() {
        assert !db.bulkBufferManager.getCurrentBuffer()
        def doc = new TestDoc()
        db.addToBulkBuffer(doc)
        assert 1 == db.bulkBufferManager.getCurrentBuffer().size()

        def results = db.flushBulkBuffer()

        assert !db.bulkBufferManager.getCurrentBuffer()
    }

    @Test
    void "test flushBulkBuffer empty buffer"() {
        assert !db.bulkBufferManager.getCurrentBuffer()

        def results = db.flushBulkBuffer()

        assert Collections.emptyList() == results
    }

    @Test
    void "test executeBulk delete"() {
        def testDoc = createTestDoc()
        assert testDoc.id
        def id = testDoc.id

        def persistedDoc = db.get(TestDoc, id)
        def bulkDeleteDoc = BulkDeleteDocument.of(persistedDoc)

        def results = db.executeBulk([bulkDeleteDoc])

        assert !results

        assert !db.contains(id)
    }

    @Test
    void "test executeBulk delete out of date"() {
        def testDoc = createTestDoc()
        assert testDoc.id
        def id = testDoc.id
        def persistedDoc = db.get(TestDoc, id)
        db.update(persistedDoc)

        def results = db.executeBulk([BulkDeleteDocument.of(testDoc)])

        assert 1 == results.size()
        def result = results[0]
        assert testDoc.id == result.id
        assert null == result.revision
        assert "conflict" == result.error
        assert "Document update conflict." == result.reason
    }

    @Test
    void "test executeBulk delete badid"() {
        assert !db.contains("badid")
        def testDoc = createTestDoc()
        def bulkDeleteDoc = new BulkDeleteDocument("badid", testDoc.revision)

        def results = db.executeBulk([bulkDeleteDoc])

        assert !results
    }

    @Test
    void "test executeBulk update"() {
        def testDoc = createTestDoc()
        assert testDoc.id
        def id = testDoc.id
        def rev = testDoc.revision

        def results = db.executeBulk([testDoc])

        assert !results

        testDoc = db.get(TestDoc, id)
        assert rev != testDoc.revision
    }

    @Test
    void "test executeBulk update out of date"() {
        def testDoc = createTestDoc()
        assert testDoc.id
        def id = testDoc.id
        def persistedDoc = db.get(TestDoc, id)
        db.update(persistedDoc)
        def rev = persistedDoc.revision
        assert testDoc.revision != persistedDoc.revision

        def results = db.executeBulk([testDoc])

        assert 1 == results.size()
        def result = results[0]
        assert testDoc.id == result.id
        assert null == result.revision
        assert "conflict" == result.error
        assert "Document update conflict." == result.reason
        assert rev == db.get(TestDoc, id).revision
    }

    @Test
    void "test executeBulk create"() {
        def testDoc = new TestDoc()

        def results = db.executeBulk([testDoc])

        assert !results
        assert testDoc.id
        assert testDoc.revision
    }

    @Test
    void "test executeBulk some fail"() {
        def testDoc1 = createTestDoc()
        assert testDoc1.id
        def id = testDoc1.id
        def persistedDoc = db.get(TestDoc, id)
        db.update(persistedDoc)

        def testDoc2 = new TestDoc()

        def results = db.executeBulk([testDoc1, testDoc2])

        assert 1 == results.size()
        def result = results[0]
        assert testDoc1.id == result.id
        assert null == result.revision
        assert "conflict" == result.error
        assert "Document update conflict." == result.reason

        assert testDoc2.id
        assert testDoc2.revision
        assert db.get(TestDoc, testDoc2.id)
    }

    @Test
    void "test executeAllOrNothing some fail"() {
        def testDoc1 = createTestDoc()
        assert testDoc1.id
        def id = testDoc1.id
        def rev = testDoc1.revision
        def age = testDoc1.age
        def newAge = age + 1
        def persistedDoc = db.get(TestDoc, id)
        db.update(persistedDoc)
        assert testDoc1.revision != persistedDoc.revision
        testDoc1.age = newAge

        def testDoc2 = new TestDoc()

//        I'm still unclear what the expected behavior is here.
        def results = db.executeAllOrNothing([testDoc1, testDoc2])
        assert !results

        def bulkUpdatedDoc = db.get(TestDoc, id)
        assert age == bulkUpdatedDoc.age
        assert persistedDoc.revision == bulkUpdatedDoc.revision

        assert testDoc2.id
        assert testDoc2.revision
    }

    @Test
    void "test changes"() {

    }

    private createTestDocDesignDocument() {
        def designDoc
        def stdDesignDocumentId = NameConventions.designDocName(TestDoc)
        def designDocumentFactory = new StdDesignDocumentFactory()
        if (db.contains(stdDesignDocumentId)) {
            designDoc = designDocumentFactory.getFromDatabase(db, stdDesignDocumentId);
        } else {
            designDoc = designDocumentFactory.newDesignDocumentInstance();
            designDoc.setId(stdDesignDocumentId);
        }

        def allView = new DesignDocument.View("""
            function(doc) {
                if (doc.type == 'TestDoc')
                    emit( doc._id, doc._id )
            }
        """)
        def byName = new DesignDocument.View("""
            function(doc) {
                if (doc.type == 'TestDoc')
                    emit( doc.name, doc._id )
            }
        """)
        def totalAgeByNameView = new DesignDocument.View("""
            function(doc) {
                if (doc.type == 'TestDoc')
                    emit( doc.name, doc.age )
            }""",  """
            function(key, values, rereduce) {
                return sum(values);
            }
            """)
        def builtInTotalAgeByNameView = new DesignDocument.View("""
            function(doc) {
                if (doc.type == 'TestDoc')
                    emit( doc.name, doc.age )
            }""",  "_sum")
        def builtInCountView = new DesignDocument.View("""
            function(doc) {
                if (doc.type == 'TestDoc')
                    emit( doc.name, doc.age )
            }""",  "_count")
        def builtInStatsView = new DesignDocument.View("""
            function(doc) {
                if (doc.type == 'TestDoc')
                    emit( doc.name, doc.age )
            }""",  "_stats")
        designDoc.views = [
                "all": allView,
                "byName": byName,
                "totalAgeByName": totalAgeByNameView,
                "builtInTotalAgeByNameView": builtInTotalAgeByNameView,
                "builtInCountView": builtInCountView,
                "builtInStatsView": builtInStatsView
        ]
        db.update(designDoc)
        return designDoc
    }

    protected ViewQuery createQuery(String designDocId, String viewName) {
        return new ViewQuery()
                .dbPath(db.path())
                .designDocId(designDocId)
                .viewName(viewName);
    }

    private int revisionToInt(String s) {
        return s.split("-")[0] as int
    }

    private def createTestDoc() {
        def testDoc = new TestDoc(name: "Jason", age: 31)
        assert !testDoc.id
        db.create(testDoc)
        assert testDoc.id
        assert testDoc.revision
        return testDoc
    }


    static class TestDoc extends CouchDbDocument {
        String name
        int age
        @TypeDiscriminator
        String type = this.class.simpleName
    }

    static class TestBook extends CouchDbDocument {
        String title
        String author
        @TypeDiscriminator
        String type = this.class.simpleName
    }


}
